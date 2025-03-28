package de.halcony.appanalyzer

import com.mchange.v3.concurrent.BoundedExecutorService
import de.halcony.appanalyzer.analysis.Analysis
import de.halcony.appanalyzer.analysis.plugin.{ActorPlugin, PluginManager}
import de.halcony.appanalyzer.appbinary.{AppManifest, MobileApp}
import de.halcony.appanalyzer.database.Postgres
import de.halcony.appanalyzer.platform.appium.Appium
import de.halcony.appanalyzer.platform.device.{
  AndroidDeviceDroidbot,
  AndroidDeviceNonRoot,
  AndroidEmulatorRoot,
  Device
}
import de.halcony.appanalyzer.platform.exceptions.FatalError
import de.halcony.appanalyzer.platform.{PlatformOperatingSystems, device}
import de.halcony.argparse.{
  OptionalValue,
  Parser,
  ParsingException,
  ParsingResult
}
import scalikejdbc.scalikejdbcSQLInterpolationImplicitDef
import wvlet.log.LogSupport

import java.io.File
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.io.Source
import scala.io.StdIn.readLine
import scala.sys.process._

object AppAnalyzer extends LogSupport {

  private val executorService = new BoundedExecutorService(
    Executors.newFixedThreadPool(10), // a pool of ten Threads
    100, // block new tasks when 100 are in process
    50 // restart accepting tasks when the number of in-process tasks falls below 50
  )
  private implicit val executionContext: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(executorService)

  private val parser: Parser = Parser(
    "AppAnalyzer",
    "run apps and analyze their consent dialogs"
  )
    .addFlag(
      "verbose",
      "v",
      "verbose",
      "if set a stacktrace is provided with any fatal error"
    )
    .addOptional(
      "config",
      "c",
      "config",
      Some("./config.json"),
      " the configuration file"
    )
    .addSubparser(PluginManager.parser)
    .addSubparser(
      Parser("removedAnalysis", "delete listed analysis ids")
        .addPositional(
          "analysisIds",
          "csv list of ids or file containing list of ids"
        )
        .addDefault[(ParsingResult, Config) => Unit]("func", deleteAnalysisMain)
    )
    .addSubparser(
      Parser("run", "run an action/analysis")
        .addPositional(
          "platform",
          "the platform to be analyzed [android_device,android_device_non_root,android_device_droidbot,android_emulator_root,ios]"
        )
        .addPositional(
          "path",
          "path to the required data for the chosen action"
        )
        .addOptional(
          "manifest",
          "m",
          "manifest",
          None,
          "the path to the manifest to be used for this run"
        )
        .addOptional(
          "device",
          "d",
          "device",
          None,
          "if provided specifies the devices in adb"
        )
        .addSubparser(AppManifest.parser)
        .addSubparser(
          Parser(
            "functionalityCheck",
            "run through all fundamental API actions to check if it works"
          )
            .addDefault[(ParsingResult, Config) => Unit](
              "func",
              functionalityCheck
            )
        )
        .addSubparser(
          Parser("plugin", "run an analysis using a plugin")
            .addPositional(
              "plugin",
              "the name of the actor plugin providing the analysis capabilities"
            )
            .addFlag(
              "ephemeral",
              "e",
              "ephemeral",
              "if set the experiment will be deleted directly after execution"
            )
            .addFlag(
              "empty",
              "w",
              "without-app",
              "if set then no app is installed and the analysis is run on the raw OS"
            )
            .addOptional(
              "only",
              "o",
              "only",
              None,
              "a file or a csv list listing app ids that shall be analyzed (any other app is ignored)"
            )
            .addOptional(
              "description",
              "d",
              "description",
              Some(""),
              "an optional experiment description"
            )
            .addOptional(
              "batchSize",
              "b",
              "batch",
              None,
              "limit the amount of apps that are analyzed in bulk"
            )
            .addOptional(
              "continue",
              "r",
              "resume",
              None,
              "provides the experiment to be continued"
            )
            .addOptional(
              "parameters",
              "p",
              "parameters",
              None,
              "a csv list of <key>=<value> pairs"
            )
            .addFlag(
              "no-app-start-check",
              "n",
              "no-app-start-check",
              "if set there is no check if the app is running"
            )
            .addDefault[(ParsingResult, Config) => Unit](
              "func",
              runPluginExperiment,
              "runs an experiment using the specified plugin"
            )
        )
    )
  // private object IgnoreMe extends Throwable

  /** main function parsing config and command line
    *
    * @param args
    *   the command line args provided by the jvm
    */
  def main(args: Array[String]): Unit = {
    try {
      val pargs: ParsingResult = parser.parse(args)
      try {
        val conf = Config.parse(pargs.getValue[String]("config"))
        pargs.getValue[(ParsingResult, Config) => Unit]("func")(pargs, conf)
      } catch {
        case x: Throwable =>
          error(
            x.getMessage + (if (pargs.getValue[Boolean]("verbose"))
                              "\n" + x.getStackTrace.mkString("\n")
                            else "")
          )
      }
    } catch {
      case _: ParsingException =>
    }
  }

  /** retrieves the optional batch size stating the number of app analyzed in a bulk
  *
  * @param pargs 
  *   the parsed command line arguments
  * @return an Option containing the batch size as an integer, if present and valid
  */
  def getBatchSize(pargs: ParsingResult): Option[Int] = {
    try {
      Some(pargs.getValue[String]("batchSize").toInt)
    } catch {
      case _: Throwable => None
    }
  }

  /** the main to delete the provided analysis ids
    *
    * @param pargs
    *   the parsed command line arguments
    * @param conf
    *   the provided configuration
    */
  def deleteAnalysisMain(pargs: ParsingResult, conf: Config): Unit = {
    Postgres.initializeConnectionPool(conf)
    val delete = Source.fromFile(pargs.getValue[String]("path"))
    try {
      val json: List[Int] =
        if (new File(pargs.getValue[String]("analysisIds")).exists()) {
          val source = Source.fromFile(pargs.getValue[String]("analysisIds"))
          try {
            source.getLines().map(_.toInt).toList
          } finally {
            source.close()
          }
        } else {
          pargs.getValue[String]("analysisIds").split(",").map(_.toInt).toList
        }
      println(
        s"shall we really delete those [${json.length}] analysis: ${json.mkString(",")}"
      )
      val userInput = readLine("[Yes/No]")
      if (userInput.trim.toLowerCase == "yes") {
        Postgres.withDatabaseSession { implicit session =>
          json.foreach { id =>
            info(s"deleting $id")
            sql"""DELETE FROM interfaceanalysis WHERE id = $id""".update.apply()
          }
        }
      }
    } finally {
      delete.close()
    }
  }

  /** creates and returns a Device instance based on the provided platform argument.
  *
  * Matches the platform string from the command-line arguments to instantiate the corresponding
  * device type (e.g., Android device with root, non-root, droidbot, emulator, or iOS).
  *
  * @param pargs 
  *   the parsed command line arguments
  * @param conf 
  *   the configuration object
  * @return an instance of Device corresponding to the specified platform
  */
  private def getDevice(pargs: ParsingResult, conf: Config): Device = {
    pargs.getValue[String]("platform") match {
      case "android_device" =>
        device.AndroidDevice(
          conf,
          pargs.get[OptionalValue[String]]("device").value
        )
      case "android_device_non_root" =>
        new AndroidDeviceNonRoot(
          conf,
          pargs.get[OptionalValue[String]]("device").value
        )
      case "android_device_droidbot" =>
        new AndroidDeviceDroidbot(
          conf,
          pargs.get[OptionalValue[String]]("device").value
        )
      case "android_emulator_root" =>
        new AndroidEmulatorRoot(
          conf,
          pargs.get[OptionalValue[String]]("device").value
        )
      case "ios" => device.iOSDevice(conf)
      case x =>
        throw new RuntimeException(s"device type $x is not yet supported")
    }
  }

  /** filters the apps contained in the folder by already analyzed apps and
    * creates MobileApp objects
    *
    * @param manifest
    *   : the read manifest file that shall be filtered against the already
    *   analyzed apps
    * @param filtering:
    *   whether filtering is supposed to happen (if no all apps from the
    *   manifest are used)
    * @return
    *   a list of MobileApp objects
    */
  private def filterAppsInFolder(
      manifest: AppManifest,
      filtering: Boolean
  ): List[MobileApp] = {
    val alreadyChecked: Set[String] =
      if (filtering) Experiment.getAnalyzedApps.map(_.id).toSet else Set()
    manifest.getManifest
      .filter { case (key, _) =>
        !alreadyChecked.contains(key)
      }
      .values
      .toList
  }

  /** determines the subset of app IDs to analyze based on a CSV list or file input.
  *
  * If the provided string represents a valid file path, the file is read and each line is considered an app ID.
  * Otherwise, the string is split by commas to form the set of app IDs.
  *
  * @param only 
  *   string representing either a CSV list or a file path with app IDs
  * @return an Option containing a Set of app IDs to analyze, or None if not provided
  */
  private def getOnlyApps(only: Option[String]): Option[Set[String]] = {
    only match {
      case Some(onlyElement) =>
        if (new File(onlyElement).exists()) {
          val source = Source.fromFile(onlyElement)
          try {
            Some(source.getLines().toSet)
          } finally {
            source.close()
          }
        } else {
          Some(onlyElement.split(",").toSet)
        }
      case None => None
    }
  }

  /** takes the provided path and returns the limited subset of all contained
    * apps
    *
    * @param pargs
    *   the parsed command line arguments
    * @param device
    *   the device to be used
    * @param conf
    *   the parsed config
    * @return
    *   a list of mobile apps contained in the path
    */
  private def getRelevantApps(
      pargs: ParsingResult,
      device: Device,
      conf: Config,
      filtering: Boolean = true
  ): List[MobileApp] = {
    val path = pargs.getValue[String]("path")
    val manifest = pargs.get[OptionalValue[String]]("manifest").value match {
      case Some(manifestPath) =>
        AppManifest(manifestPath, device.PLATFORM_OS, update = false)(conf)
      case None =>
        AppManifest(
          path,// + "/manifest.json",
          device.PLATFORM_OS,
          update = false
        )(conf)
    }
    if (manifest.getManifest.isEmpty)
      warn(
        "the manifest appears to be empty - either your folder is empty or you have not created the manifest yet"
      )
    val apps = filterAppsInFolder(manifest, filtering)
    val appSubset = apps.slice(0, getBatchSize(pargs).getOrElse(apps.length))
    getOnlyApps(pargs.get[OptionalValue[String]]("only").value) match {
      case Some(filterList) =>
        appSubset.filter(app => filterList.contains(app.id))
      case None => appSubset
    }
  }

  /** wrapper to run arbitrary analysis for all apps contained in the batch with
    * a possible resume flag
    *
    * @param getNextActor
    *   a function creating the actor for reach analysis
    * @param pargs
    *   the parsed command line arguments
    * @param conf
    *   the configuration
    */
  private def runExperiment(
      getNextActor: => ActorPlugin,
      pargs: ParsingResult,
      conf: Config,
      empty: Boolean
  ): Unit = {
    val device = getDevice(pargs, conf)
    Postgres.initializeConnectionPool(conf)
    val description = pargs.getValue[String]("description")
    info(s"running $description")
    pargs.get[OptionalValue[String]]("continue").value match {
      case Some(value) => Experiment.loadExperiment(value.toInt)
      case None        => Experiment.createNewExperiment(description)
    }
    val mailer: Option[Mailer] = conf.email.map(new Mailer(_))
    try {
      if (!empty) {
        val apps = getRelevantApps(pargs, device, conf)
        var counter = apps.length
        apps.foreach { app =>
          info(
            s"we have $counter app${if (counter > 1) "s" else ""} to analyze"
          )
          Analysis.runAnalysis(getNextActor, app, device, conf, pargs.getValue[Boolean]("no-app-start-check"))
          counter = counter - 1
          uninstallSanityCheck(conf = conf, device = device)
        }
      } else {
        Analysis.runAnalysis(
          getNextActor,
          MobileApp("EMPTY", "EMPTY", device.PLATFORM_OS, "EMPTY"),
          device,
          conf,
          pargs.getValue[Boolean]("no-app-start-check")
        )
      }
    } catch {
      case x: FatalError =>
        mailer match {
          case Some(mailer: Mailer) =>
            mailer.send_email(
              subject = "Fatal Error",
              content = x.getMessage + "\n" + x.getStackTrace.mkString("\n")
            )
          case None =>
        }
        error(x.getMessage)
      case x: Throwable =>
        error(s"${x.getMessage} \n ${x.getStackTrace.mkString("\n")}")
        Experiment.addEncounteredError(x)
    } finally {
      device.resetDevice()
      device
        .stopDevice() // this should do anything for physical devices but stops the emulator for a clean restart
      if (pargs.getValue[Boolean]("ephemeral")) {
        Experiment.deleteCurrentExperiment()
        info("ephemeral experiment is done")
      } else {
        info(s"experiment ${Experiment.getCurrentExperiment.id} is done")
      }
      mailer match {
        case Some(mailer: Mailer) =>
          mailer.send_email(
            subject = "Experiment Done",
            content =
              s"Experiment ${Experiment.getCurrentExperiment.id} is done"
          )
        case None =>
      }
    }
  }

  /**
  * Initiates an analysis experiment using a specified plugin.
  *
  * Extracts plugin parameters, retrieves the corresponding plugin from the PluginManager,
  * and delegates the experiment execution to the runExperiment function.
  *
  * @param pargs 
  *   the parsed command line arguments
  * @param conf 
  *   the configuration object
  */
  private def runPluginExperiment(pargs: ParsingResult, conf: Config): Unit = {
    val pluginName = pargs.getValue[String]("plugin")
    val empty = pargs.getValue[Boolean]("empty")
    val parameters: Map[String, String] = extract_parameters(pargs)

    val manager = PluginManager.getPluginManager(conf)
    runExperiment(
      manager.loadPlugin(pluginName, parameters),
      pargs,
      conf,
      empty
    )
  }

  /** perform functionality check of the currently connect device
    *
    * @param pargs
    *   the parsed command line arguments
    * @param conf
    *   the parsed config file
    */
  private def functionalityCheck(pargs: ParsingResult, conf: Config): Unit = {
    println("Welcome to the functionality check!")
    println()
    println(
      "This is most likely the most important yet most frustrating feature of all."
    )
    println(
      "We will go through each element of the Device and Appium API to check if it works with the currently"
    )
    println("attached and configured device.")
    println("As the path parameter I expect a single app")
    println(
      "If something fails I might give some useful hints or just crash ¯\\_(oo)_/¯."
    )
    println("Let's start...")

    val device = getDevice(pargs, conf)
    val path: String = pargs.getValue[String]("path")
    println(s"We are supposed to work on ${device.PLATFORM_OS}")
    println(s"As our Canary App we are using $path")

    def tryApiCommand(
        description: String,
        failureHint: Option[String] = None,
        optional: Boolean = false
    )(func: => Option[String]): Unit = {
      try {
        println(s"action: $description")
        func match {
          case Some(value) => println(s"return: $value")
          case None        =>
        }
      } catch {
        case x: Throwable =>
          println(s"Failed with: ${x.getMessage}")
          println(x.getStackTrace.mkString("\n"))
          failureHint match {
            case Some(value) => println(s"Hint: $value")
            case None        =>
          }
          if (!optional)
            throw new RuntimeException(
              s"$description failed, no point in continuing"
            )
      }
      println()
    }

    tryApiCommand("device.ensureDevice") {
      device.ensureDevice()
      None
    }

    tryApiCommand("device.startFrida") {
      device.startFrida()
      None
    }

    tryApiCommand("device.restartPhone") {
      None
    }

    tryApiCommand("device.getAppPackageAnalysis") {
      device.getAppPackageAnalysis(conf)
      None
    }

    val app = appbinary.MobileApp("", "", PlatformOperatingSystems.IOS, path)

    tryApiCommand("appPackageAnalysis.getAppId") {
      Some(device.getAppPackageAnalysis(conf).getAppId(app, None))
    }

    tryApiCommand("installApp") {
      device.installApp(app)
      None
    }

    val appId = device.getAppPackageAnalysis(conf).getAppId(app, None)

    tryApiCommand("grantPermissios") {
      device.setAppPermissions(appId)
      None
    }

    tryApiCommand("startApp") {
      device.startApp(appId, noStartCheck = false)
      Some(s"app running: ${device.getForegroundAppId.get == appId}")
    }

    tryApiCommand("getPid") {
      Some(device.getPid(appId))
    }

    tryApiCommand("getPrefs") {
      device.getPrefs(appId)
    }

    tryApiCommand("getPlatformSpecificData") {
      device.getPlatformSpecificData(appId)
    }

    println("seems like everything is in working order")
    println(
      "now we are going into some more involved features corresponding to our promised functionalities"
    )

    // using appium to retrieve elements (which implies that we can also interact)
    tryApiCommand("appium.getElements") {
      Appium.withRunningAppium(appId, conf, device) { appium =>
        Some(
          appium
            .findElementsByXPath("//*")
            .map(_.getText.trim)
            .filter(_ != "")
            .mkString("\n")
        )
      }
    }

    // everything works as intended
    tryApiCommand("stopApp") {
      device.closeApp(appId)
      Some(
        s"app running: ${device.getForegroundAppId.getOrElse("nobody") == appId}"
      )
    }

    tryApiCommand("device.stopFrida") {
      device.stopFrida()
      None
    }

    tryApiCommand("uninstallApp") {
      device.uninstallApp(appId)
      None
    }
  }

  /** extracts key-value parameters for analysis from the parsed command-line arguments.
  *
  * Supports both direct CSV input and file input containing key=value pairs.
  * Parses the input into a Map of parameter keys and values. Throws an exception if any
  * element is malformed.
  *
  * @param pargs 
  *   the parsed command line arguments
  * @return a Map containing the parameter keys and their corresponding values
  */
  private def extract_parameters(pargs: ParsingResult): Map[String, String] = {
    val parameters: Option[String] =
      pargs.get[OptionalValue[String]]("parameters").value
    parameters match {
      case Some(keyvaluecsv) =>
        if (is_filepath(keyvaluecsv)) {
          val read_csv = Source.fromFile(keyvaluecsv)
          try {
            read_csv
              .getLines()
              .flatMap(line =>
                line.split("=") match {
                  case Array(key, value) => Some(key.trim -> value.trim)
                  case _                 => None
                }
              )
              .toMap
          } finally {
            read_csv.close()
          }
        } else {
          keyvaluecsv
            .split(",")
            .map { keyValue =>
              keyValue.split("=").toList match {
                case key :: value :: Nil => key -> value
                case x =>
                  throw new RuntimeException(
                    s"element $keyValue of $keyvaluecsv has malformed split: $x"
                  )
              }
            }
            .toMap
        }
      case None => Map[String, String]()
    }
  }

  /** checks if the provided string represents an existing file path.
  *
  * @param str 
  *   the string to check as a file path
  * @return true if the file exists, false otherwise
  */
  private def is_filepath(str: String): Boolean = {
    new File(str).exists()
  }

  //todo: this needs to be moved into the device API
  private def uninstallSanityCheck(conf: Config, device: Device): Unit = {
    val initiallyInstalledApps = device.initiallyInstalledApps
    initiallyInstalledApps match {
      case Some(initiallyInstalledApps) =>
        var currentlyInstalledApps = device.getInstalledApps
        var diff = initiallyInstalledApps.diff(currentlyInstalledApps)
        info(s"Uninstall sanity check")
        diff.foreach(app => {
          warn(s"detected non-initial app $app")
          val cmd = s"${conf.android.adb} uninstall $app"
          val _ = cmd.!!
        })
        currentlyInstalledApps = device.getInstalledApps
        diff = initiallyInstalledApps.diff(currentlyInstalledApps)
        if (diff.nonEmpty) {
          warn(s"Uninstall sanity check failed")
          diff.foreach(app => {
            warn(s"Uninstall sanity check failed for $app")
          })
        } else {
          info(s"Uninstall sanity check passed")
        }
      case None =>
        info("No sanity check performed initiallyInstalledApps are empty")
    }

  }
}
