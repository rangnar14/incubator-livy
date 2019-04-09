/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.livy.utils

import java.util.concurrent.TimeoutException

import io.fabric8.kubernetes.api.model.{Pod, ConfigBuilder ⇒ _}
import io.fabric8.kubernetes.client._
import org.apache.livy.{LivyConf, Logging, Utils}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

object SparkKubernetesApp extends Logging {

  // KubernetesClient is thread safe. Create once, share it across threads.
  private lazy val kubernetesClient: DefaultKubernetesClient = KubernetesClientFactory.createKubernetesClient(livyConf)

  private val leakedAppTags = new java.util.concurrent.ConcurrentHashMap[String, Long]()

  private val leakedAppsGCThread = new Thread() {
    override def run(): Unit = {
      import KubernetesExtensions._
      while (true) {
        if (!leakedAppTags.isEmpty) {
          // kill the app if found it and remove it if exceeding a threshold
          val iter = leakedAppTags.entrySet().iterator()
          var isRemoved = false
          val now = System.currentTimeMillis()
          val apps = kubernetesClient.getApplications()
          while (iter.hasNext) {
            val entry = iter.next()
            apps.find(_.getApplicationTag.contains(entry.getKey))
              .foreach({
                app =>
                  info(s"Kill leaked app ${app.getApplicationId}")
                  kubernetesClient.killApplication(app)
                  iter.remove()
                  isRemoved = true
              })
            if (!isRemoved) {
              if ((entry.getValue - now) > sessionLeakageCheckTimeout) {
                iter.remove()
                info(s"Remove leaked Kubernetes app tag ${entry.getKey}")
              }
            }
          }
        }
        Thread.sleep(sessionLeakageCheckInterval)
      }
    }
  }

  private var livyConf: LivyConf = _

  private var cacheLogSize    : Int            = _
  private var appLookupTimeout: FiniteDuration = _
  private var pollInterval    : FiniteDuration = _

  private var sessionLeakageCheckTimeout : Long = _
  private var sessionLeakageCheckInterval: Long = _

  def init(livyConf: LivyConf): Unit = {
    this.livyConf = livyConf

    cacheLogSize = livyConf.getInt(LivyConf.SPARK_LOGS_SIZE)
    appLookupTimeout = livyConf.getTimeAsMs(LivyConf.KUBERNETES_APP_LOOKUP_TIMEOUT).milliseconds
    pollInterval = livyConf.getTimeAsMs(LivyConf.KUBERNETES_POLL_INTERVAL).milliseconds

    sessionLeakageCheckInterval = livyConf.getTimeAsMs(LivyConf.KUBERNETES_APP_LEAKAGE_CHECK_INTERVAL)
    sessionLeakageCheckTimeout = livyConf.getTimeAsMs(LivyConf.KUBERNETES_APP_LEAKAGE_CHECK_TIMEOUT)

    leakedAppsGCThread.setDaemon(true)
    leakedAppsGCThread.setName("LeakedAppsGCThread")
    leakedAppsGCThread.start()
  }

}

class SparkKubernetesApp private[utils](
    appTag: String,
    appIdOption: Option[String],
    process: Option[LineBufferedProcess],
    listener: Option[SparkAppListener],
    livyConf: LivyConf,
    kubernetesClient: => KubernetesClient = SparkKubernetesApp.kubernetesClient) // For unit test.
  extends SparkApp
    with Logging {

  import SparkKubernetesApp._
  import KubernetesExtensions._

  private        val appIdPromise         : Promise[String]    = Promise()
  private[utils] var state                : SparkApp.State     = SparkApp.State.STARTING
  private        var kubernetesDiagnostics: IndexedSeq[String] = IndexedSeq.empty[String]
  private        var kubernetesAppLog     : IndexedSeq[String] = IndexedSeq.empty[String]

  // Exposed for unit test.
  // TODO Instead of spawning a thread for every session, create a centralized thread and
  // batch Kubernetes queries.
  private[utils] val kubernetesAppMonitorThread = Utils.startDaemonThread(s"kubernetesAppMonitorThread-$this") {
    try {
      // If appId is not known, query Kubernetes by appTag to get it.
      val appId = try {
        appIdOption.getOrElse {
          val deadline = appLookupTimeout.fromNow
          getAppIdFromTag(appTag, pollInterval, deadline)
        }
      } catch {
        case e: Exception =>
          appIdPromise.failure(e)
          throw e
      }
      appIdPromise.success(appId)

      Thread.currentThread().setName(s"kubernetesAppMonitorThread-$appId")
      listener.foreach(_.appIdKnown(appId.toString))

      var appInfo = AppInfo()
      while (isRunning) {
        try {
          Clock.sleep(pollInterval.toMillis)

          // Refresh application state
          val appReport = kubernetesClient.getApplicationReport(appTag, cacheLogSize = cacheLogSize)
          kubernetesAppLog = appReport.getApplicationLog
          kubernetesDiagnostics = appReport.getApplicationDiagnostics
          changeState(mapKubernetesState(appReport.getApplicationState, appTag))

          val latestAppInfo = AppInfo(sparkUiUrl = Option(appReport.getTrackingUrl))
          if (appInfo != latestAppInfo) {
            listener.foreach(_.infoChanged(latestAppInfo))
            appInfo = latestAppInfo
          }
        } catch {
          // TODO analyse available exceptions
          case e: Throwable ⇒
            throw e
        }
      }
      debug(s"$appId $state ${kubernetesDiagnostics.mkString(" ")}")
      val latestAppInfo = AppInfo(sparkUiUrl = Option(buildHistoryServerUiUrl(livyConf, appId)))
      if (appInfo != latestAppInfo) {
        listener.foreach(_.infoChanged(latestAppInfo))
        appInfo = latestAppInfo
      }
    } catch {
      case _: InterruptedException =>
        kubernetesDiagnostics = ArrayBuffer("Session stopped by user.")
        changeState(SparkApp.State.KILLED)
      case NonFatal(e)             =>
        error(s"Error whiling refreshing Kubernetes state", e)
        kubernetesDiagnostics = ArrayBuffer(e.getMessage)
        changeState(SparkApp.State.FAILED)
    }
  }

  override def log(): IndexedSeq[String] =
    ("stdout: " +: kubernetesAppLog) ++
    ("\nstderr: " +: (process.map(_.inputLines).getOrElse(ArrayBuffer.empty[String]) ++ process.map(_.errorLines).getOrElse(ArrayBuffer.empty[String]))) ++
    ("\nKubernetes Diagnostics: " +: kubernetesDiagnostics)

  override def kill(): Unit = synchronized {
    if (isRunning) {
      try {
        kubernetesClient.killApplication(appTag)
      } catch {
        // We cannot kill the Kubernetes app without the appTag.
        // There's a chance the Kubernetes app hasn't been submitted during a livy-server failure.
        // We don't want a stuck session that can't be deleted. Emit a warning and move on.
        case _: TimeoutException | _: InterruptedException =>
          warn("Deleting a session while its Kubernetes application is not found.")
          kubernetesAppMonitorThread.interrupt()
      } finally {
        process.foreach(_.destroy())
      }
    }
  }

  private def isRunning: Boolean = {
    state != SparkApp.State.FAILED && state != SparkApp.State.FINISHED && state != SparkApp.State.KILLED
  }

  private def changeState(newState: SparkApp.State.Value): Unit = {
    if (state != newState) {
      listener.foreach(_.stateChanged(state, newState))
      state = newState
    }
  }

  /**
    * Find the corresponding Kubernetes application id from an application tag.
    *
    * @param appTag The application tag tagged on the target application.
    *               If the tag is not unique, it returns the first application it found.
    *               It will be converted to the appropriate format to match Kubernetes's behaviour.
    *
    * @return applicationId String or the failure.
    */
  @tailrec
  private def getAppIdFromTag(
      appTag: String,
      pollInterval: Duration,
      deadline: Deadline): String = {
    import KubernetesExtensions._

    kubernetesClient.getApplications().find(_.getApplicationTag.contains(appTag))
    match {
      case Some(app) => app.getApplicationId
      case None      =>
        if (deadline.isOverdue) {
          process.foreach(_.destroy())
          leakedAppTags.put(appTag, System.currentTimeMillis())
          throw new IllegalStateException(s"No Kubernetes application is found with tag" +
            s" $appTag in ${livyConf.getTimeAsMs(LivyConf.KUBERNETES_APP_LOOKUP_TIMEOUT) / 1000}" +
            " seconds. This may be because 1) spark-submit fail to submit application to Kubernetes; " +
            "or 2) Kubernetes cluster doesn't have enough resources to start the application in time. " +
            "Please check Livy log and Kubernetes log to know the details.")
        } else {
          Clock.sleep(pollInterval.toMillis)
          getAppIdFromTag(appTag, pollInterval, deadline)
        }
    }
  }

  // Exposed for unit test.
  private[utils] def mapKubernetesState(kubernetesAppState: String, appTag: String): SparkApp.State.Value = {
    import KubernetesApplicationState._
    kubernetesAppState.toLowerCase match {
      case PENDING | CONTAINER_CREATING =>
        SparkApp.State.STARTING
      case RUNNING                      =>
        SparkApp.State.RUNNING
      case COMPLETED | SUCCEEDED        =>
        SparkApp.State.FINISHED
      case FAILED | ERROR               =>
        SparkApp.State.FAILED
      case other                        => // any other combination is invalid, so FAIL the application.
        error(s"Unknown Kubernetes state $other for app with tag $appTag.")
        SparkApp.State.FAILED
    }
  }

  private def buildHistoryServerUiUrl(livyConf: LivyConf, appId: String): String =
    s"${livyConf.get(LivyConf.UI_HISTORY_SERVER_URL)}/history/$appId/jobs/"

}

object KubernetesApplicationState {
  val PENDING            = "pending"
  val CONTAINER_CREATING = "containercreating"
  val RUNNING            = "running"
  val COMPLETED          = "completed"
  val SUCCEEDED          = "succeeded"
  val FAILED             = "failed"
  val ERROR              = "error"
}

object KubernetesConstants {
  val SPARK_APP_ID_LABEL  = "spark-app-selector"
  val SPARK_APP_TAG_LABEL = "spark-app-tag"
  val SPARK_ROLE_LABEL    = "spark-role"
  val SPARK_ROLE_DRIVER   = "driver"
  val SPARK_ROLE_EXECUTOR = "executor"
  val SPARK_UI_URL_LABEL  = "spark-ui-url"
}

class KubernetesApplication(driverPod: Pod) {

  import KubernetesConstants._

  private val appTag = driverPod.getMetadata.getLabels.get(SPARK_APP_TAG_LABEL)
  private val appId  = driverPod.getMetadata.getLabels.get(SPARK_APP_ID_LABEL)

  def getApplicationTag: String = appTag

  def getApplicationId: String = appId

  def getApplicationPod: Pod = driverPod
}

class KubernetesAppReport(driver: Pod, executors: Seq[Pod], appLog: IndexedSeq[String]) {

  def getApplicationState: String = Try(driver.getStatus.getPhase.toLowerCase).getOrElse("unknown")

  def getApplicationLog: IndexedSeq[String] = appLog

  def getTrackingUrl: String = Try {
    driver.getMetadata.getLabels.getOrDefault(KubernetesConstants.SPARK_UI_URL_LABEL, "localhost:4040")
  } getOrElse "localhost:4040"

  def getApplicationDiagnostics: IndexedSeq[String] = {
    Try(
      (Seq(driver) ++ executors.sortBy(_.getMetadata.getName))
        .filter(_ != null)
        .map(buildSparkPodDiagnosticsPrettyString).mkString("\n")
    )
      .filter(_.nonEmpty)
      .map[IndexedSeq[String]](_.split("\n"))
      .getOrElse(IndexedSeq.empty)
  }

  private def buildSparkPodDiagnosticsPrettyString(pod: Pod): String = {
    import scala.collection.JavaConverters._
    def printMap(map: Map[_, _]): String = map.map {
      case (key, value) ⇒ s"$key=$value"
    }.mkString(", ")

    if (pod == null) return "unknown"

    s"${pod.getMetadata.getName}.${pod.getMetadata.getNamespace}:" +
      s"\n\tnode: ${pod.getSpec.getNodeName}" +
      s"\n\thostname: ${pod.getSpec.getHostname}" +
      s"\n\tpodIp: ${pod.getStatus.getPodIP}" +
      s"\n\tstartTime: ${pod.getStatus.getStartTime}" +
      s"\n\tphase: ${pod.getStatus.getPhase}" +
      s"\n\treason: ${pod.getStatus.getReason}" +
      s"\n\tmessage: ${pod.getStatus.getMessage}" +
      s"\n\tlabels: ${printMap(pod.getMetadata.getLabels.asScala.toMap)}" +
      s"\n\tcontainers:" +
      s"\n\t\t${
        pod.getSpec.getContainers.asScala.map(container ⇒
          s"${container.getName}:" +
            s"\n\t\t\timage: ${container.getImage}" +
            s"\n\t\t\trequests: ${printMap(container.getResources.getRequests.asScala.toMap)}" +
            s"\n\t\t\tlimits: ${printMap(container.getResources.getLimits.asScala.toMap)}" +
            s"\n\t\t\tcommand: ${container.getCommand} ${container.getArgs}"
        ).mkString("\n\t\t")
      }" +
      s"\n\tconditions:" +
      s"\n\t\t${pod.getStatus.getConditions.asScala.mkString("\n\t\t")}"
  }

}

object KubernetesExtensions {

  import KubernetesConstants._

  implicit class KubernetesClientExtensions(client: KubernetesClient) {

    import scala.collection.JavaConverters._
    import io.fabric8.kubernetes.client.dsl.PodResource
    import io.fabric8.kubernetes.api.model.DoneablePod

    def getApplications(
        labels: Map[String, String] = Map(SPARK_ROLE_LABEL → SPARK_ROLE_DRIVER),
        appTagLabel: String = SPARK_APP_TAG_LABEL,
        appIdLabel: String = SPARK_APP_ID_LABEL
    ): Seq[KubernetesApplication] = {
      client.pods.inAnyNamespace
        .withLabels(labels.asJava)
        .withLabel(appTagLabel)
        .withLabel(appIdLabel)
        .list.getItems.asScala.map(new KubernetesApplication(_))
    }

    def killApplication(app: KubernetesApplication): Boolean = {
      client.pods.inAnyNamespace.delete(app.getApplicationPod)
    }

    def killApplication(
        appTag: String,
        appTagLabel: String = SPARK_APP_TAG_LABEL,
        labels: Map[String, String] = Map(SPARK_ROLE_LABEL → SPARK_ROLE_DRIVER)
    ): Boolean = {
      client.pods.inAnyNamespace.withLabels((labels ++ Map(appTagLabel → appTag)).asJava).delete()
    }

    def getApplicationReport(
        appTag: String,
        cacheLogSize: Int,
        appTagLabel: String = SPARK_APP_TAG_LABEL
    ): KubernetesAppReport = Try {
      val pods = client.pods.inAnyNamespace.withLabels(Map(appTagLabel → appTag).asJava).list.getItems.asScala
      val driver = pods.find(_.getMetadata.getLabels.get(SPARK_ROLE_LABEL) == SPARK_ROLE_DRIVER).orNull
      val executors = pods.filter(_.getMetadata.getLabels.get(SPARK_ROLE_LABEL) == SPARK_ROLE_EXECUTOR)
      val appLog = getApplicationLog(driver, cacheLogSize)
      new KubernetesAppReport(driver, executors, appLog)
    } getOrElse new KubernetesAppReport(null, Seq.empty, IndexedSeq.empty)

    def getApplicationLog(pod: Pod, cacheLogSize: Int): IndexedSeq[String] = try {
      getPodResource(pod).tailingLines(cacheLogSize).getLog.split("\n").toIndexedSeq
    } catch {
      case e: Throwable ⇒
        ArrayBuffer(e.toString +: e.getStackTrace.map(_.toString): _*)
    }

    private def getPodResource(pod: Pod): PodResource[Pod, DoneablePod] = {
      val name = pod.getMetadata.getName
      val namespace = pod.getMetadata.getNamespace
      client.pods.inNamespace(namespace).withName(name)
    }

  }

}

object KubernetesClientFactory {

  import com.google.common.base.Charsets
  import com.google.common.io.Files
  import java.io.File

  implicit class OptionString(val string: String) extends AnyVal {
    def toOption: Option[String] = if (string == null || string.isEmpty) None else Option(string)
  }

  def createKubernetesClient(livyConf: LivyConf): DefaultKubernetesClient = {
    val masterUrl = livyConf.get(LivyConf.KUBERNETES_MASTER_URL)

    val oauthTokenFile = livyConf.get(LivyConf.KUBERNETES_OAUTH_TOKEN_FILE).toOption
    val oauthTokenValue = livyConf.get(LivyConf.KUBERNETES_OAUTH_TOKEN_VALUE).toOption
    require(oauthTokenFile.isEmpty || oauthTokenValue.isEmpty,
      s"Cannot specify OAuth token through both a file $oauthTokenFile and a value $oauthTokenValue.")

    val caCertFile = livyConf.get(LivyConf.KUBERNETES_CA_CERT_FILE).toOption
    val clientKeyFile = livyConf.get(LivyConf.KUBERNETES_CLIENT_KEY_FILE).toOption
    val clientCertFile = livyConf.get(LivyConf.KUBERNETES_CLIENT_CERT_FILE).toOption

    val config = new ConfigBuilder()
      .withApiVersion("v1")
      .withMasterUrl(masterUrl)
      .withOption(oauthTokenValue) {
        (token, configBuilder) => configBuilder.withOauthToken(token)
      }
      .withOption(oauthTokenFile) {
        (filePath, configBuilder) => configBuilder.withOauthToken(Files.toString(new File(filePath), Charsets.UTF_8))
      }
      .withOption(caCertFile) {
        (file, configBuilder) => configBuilder.withCaCertFile(file)
      }
      .withOption(clientKeyFile) {
        (file, configBuilder) => configBuilder.withClientKeyFile(file)
      }
      .withOption(clientCertFile) {
        (file, configBuilder) => configBuilder.withClientCertFile(file)
      }
      .build()
    new DefaultKubernetesClient(config)
  }

  private implicit class OptionConfigurableConfigBuilder(val configBuilder: ConfigBuilder) extends AnyVal {
    def withOption[T]
    (option: Option[T])
      (configurator: (T, ConfigBuilder) => ConfigBuilder): ConfigBuilder = {
      option.map {
        opt => configurator(opt, configBuilder)
      }.getOrElse(configBuilder)
    }
  }

}