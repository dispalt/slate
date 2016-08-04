import sbt._
import Keys._
import net.lullabyte.Chrome
import org.scalajs.sbtplugin.{OptimizerOptions, ScalaJSPlugin}
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.scalajs.sbtplugin.cross.CrossProject
import scoverage.ScoverageKeys.coverageExcludedPackages
import upickle.Js

object DashboarderBuild extends Build {

  val commonDeps = Seq(
    libraryDependencies ++= Seq(
      "org.scalatest" %%% "scalatest" % "3.0.0-RC4" % "test",
      "com.lihaoyi" %%% "upickle" % "0.4.0",
      "com.lihaoyi" %%% "pprint" % "0.4.0",
      "com.lihaoyi" %%% "fastparse" % "0.3.7",
      "io.monix" %%% "monix" % "2.0-RC8",
      "io.monix" %%% "monix-scalaz-72" % "2.0-RC8",
      "com.thoughtworks.each" %%% "each" % "0.5.1",
      "com.github.julien-truffaut" %%% "monocle-core" % "1.2.1",
      "com.github.julien-truffaut" %%% "monocle-generic" % "1.2.1",
      "com.github.julien-truffaut" %%% "monocle-macro" % "1.2.1",
      "com.github.julien-truffaut" %%% "monocle-state" % "1.2.1",
      "com.slamdata" %%% "matryoshka-core" % "0.11.0",
      "org.scodec" %%% "scodec-bits" % "1.1.0",
      "org.scodec" %%% "scodec-core" % "1.10.2"
    ),
    resolvers += Resolver.sonatypeRepo("releases"),
    resolvers += "Sonatype Public" at "https://oss.sonatype.org/content/groups/public/")

  val uiDeps = libraryDependencies ++= Seq(
    "net.lullabyte" %%% "scala-js-chrome" % "0.2.1",
    "org.scala-js" %%% "scalajs-dom" % "0.9.0",
    "com.github.japgolly.scalajs-react" %%% "core" % "0.11.1",
    "com.github.japgolly.scalajs-react" %%% "ext-scalaz72" % "0.11.1",
    "com.github.japgolly.scalajs-react" %%% "ext-monocle" % "0.11.1",
    "com.github.japgolly.scalacss" %%% "ext-react" % "0.4.1"
  )

  // React JS itself (Note the filenames, adjust as needed, eg. to remove addons.)
  val jsDeps = jsDependencies ++= Seq(
    "org.webjars.bower" % "react" % "15.0.2"
      / "react-with-addons.js"
      minified "react-with-addons.min.js"
      commonJSName "React",

    "org.webjars.bower" % "react" % "15.0.2"
      / "react-dom.js"
      minified "react-dom.min.js"
      dependsOn "react-with-addons.js"
      commonJSName "ReactDOM",

    "org.webjars.bower" % "react" % "15.0.2"
      / "react-dom-server.js"
      minified "react-dom-server.min.js"
      dependsOn "react-dom.js"
      commonJSName "ReactDOMServer"
  )

  val chromePackageContent = SettingKey[File]("chromePackageContent",
    "The contents of this directory get copied to the into the chrome extension")
  val chromeBuildOpt = TaskKey[File]("chromeBuildOpt")
  val chromeBuildFast = TaskKey[File]("chromeBuildFast")
  val chromeBuildUnopt = TaskKey[File]("chromeBuildUnopt")
  val chromePackage = TaskKey[File]("chromePackage")
  val chromeGenerateManifest = TaskKey[File]("chromeGenerateManifest")
  val chromeManifest = TaskKey[chrome.Manifest]("chromeManifest")
  val repl = TaskKey[Unit]("repl")

  object SnakeOptionPickle extends upickle.AttributeTagged {
    def camelToSnake(s: String) = {
      val res = s.split("(?=[A-Z])", -1).map(_.toLowerCase).mkString("_")
      res
    }
    override def CaseR[T: this.Reader, V]
    (f: T => V,
     names: Array[String],
     defaults: Array[Js.Value]) = {
      super.CaseR[T, V](f, names.map(camelToSnake), defaults)
    }
    override def CaseW[T: this.Writer, V]
    (f: V => Option[T],
     names: Array[String],
     defaults: Array[Js.Value]) = {
      super.CaseW[T, V](f, names.map(camelToSnake), defaults)
    }
    override implicit def OptionW[T: Writer]: Writer[Option[T]] = Writer {
      case None => Js.Null
      case Some(s) => implicitly[Writer[T]].write(s)
    }

    override implicit def OptionR[T: Reader]: Reader[Option[T]] = Reader {
      case Js.Null => None
      case v: Js.Value => Some(implicitly[Reader[T]].read.apply(v))
    }
  }

  def generateManifest(out: File)(manifest: ChromeManifest): File = {
    val content = SnakeOptionPickle.write(manifest, indent = 2)
    IO.write(out, content)
    out
  }

  def copyResources(suffices: Seq[String]) = Def.task {
    val file = (resourceDirectory in Compile).value
    val unpacked = suffices.foldLeft(target.value)(_ / _)
    file.listFiles().foreach { resourceFile =>
      val target = unpacked / resourceFile.getName
      if (!target.exists() || resourceFile.lastModified() > target.lastModified()) {
        if (resourceFile.isFile) {
          IO.copyFile(resourceFile, target)
        } else {
          IO.copyDirectory(resourceFile, target)
        }
      }
    }
    unpacked
  }

  lazy val chromeTasks: Seq[Def.Setting[_]] = Seq(
    chromePackageContent := file("content"),
    chromeBuildOpt := {
      val unpacked = copyResources(Seq("chrome", "unpackedopt")).value
      Chrome.buildExtentionDirectory(unpacked)(
        (chromeGenerateManifest in Compile).value,
        (fullOptJS in Compile).value.data,
        (packageMinifiedJSDependencies in Compile).value,
        (packageScalaJSLauncher in Compile).value.data,
        (chromePackageContent in Compile).value
      )
    },

    chromeBuildFast := {
      val unpacked = copyResources(Seq("chrome", "unpackedfast")).value
      Chrome.buildExtentionDirectory(unpacked)(
        (chromeGenerateManifest in Compile).value,
        (fastOptJS in Compile).value.data,
        (packageJSDependencies in Compile).value,
        (packageScalaJSLauncher in Compile).value.data,
        (chromePackageContent in Compile).value
      )
    },

    chromeBuildUnopt := {
      val unpacked = copyResources(Seq("chrome", "unpackedunopt")).value
      val oldOpts = scalaJSOptimizerOptions.value
      scalaJSOptimizerOptions := oldOpts.withDisableOptimizer(true)
      val file =
        Chrome.buildExtentionDirectory(unpacked)(
          (chromeGenerateManifest in Compile).value,
          (fastOptJS in Compile).value.data,
          (packageJSDependencies in Compile).value,
          (packageScalaJSLauncher in Compile).value.data,
          (chromePackageContent in Compile).value
        )
      scalaJSOptimizerOptions := oldOpts
      file
    },

    chromePackage := {
      val out = target.value / "chrome"
      val chromeAppDir = chromeBuildOpt.value
      val zipFile = new File(out, s"${name.value}.zip")
      IO.zip(allSubpaths(chromeAppDir), zipFile)
      zipFile
    },

    chromeGenerateManifest := {
      generateManifest(target.value / "chrome" / "generated_manifest.json")(ChromeManifest.mySettings)
    }
  )

  lazy val replMain =
    mainClass in(Compile, run) := Some("qq.InterpreterMain")

  val unitTest = TaskKey[Unit]("unitTest")
  val itTest = TaskKey[Unit]("itTest")
  //val unitTestQuick = TaskKey[Unit]("unitTestQuick")
  //val itTestQuick = TaskKey[Unit]("itTestQuick")

  val baseSettings: Seq[sbt.Def.Setting[_]] = Seq(
    version := "0.0.1",
    scalaVersion := "2.11.8",
    scalacOptions ++= Seq(
      "-Xlint",
      "-feature",
      "-language:higherKinds",
      "-Ywarn-value-discard",
      "-Ywarn-adapted-args",
      "-Ywarn-adapted-args",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit"
    ),
    coverageExcludedPackages := ";qq.*Main;",
    persistLauncher in Compile := true,
    persistLauncher in Test := false,
    unitTest <<= {
      (testOnly in Test).toTask(" -- -l WebTestTag")
    },
    itTest <<= {
      (testOnly in Test).toTask(" -- -n WebTestTag")
    },
    addCompilerPlugin("com.milessabin" % "si2712fix-plugin" % "1.2.0" cross CrossVersion.full),
    addCompilerPlugin("org.spire-math" % "kind-projector" % "0.8.0" cross CrossVersion.binary)
  )

  val jsSettings: Seq[sbt.Def.Setting[_]] = Seq(
    scalaJSUseRhino in Global := false,
    relativeSourceMaps := true
  )

  lazy val qq: CrossProject = crossProject.in(file("qq"))
    .settings(baseSettings: _*)
    .settings(commonDeps: _*)
    .settings(replMain: _*)
    .jsSettings(scalaJSUseRhino in Global := false)
    .jsSettings(ScalaJSPlugin.projectSettings: _*)
    .jsSettings(requiresDOM in Test := false)

  lazy val qqjvm = qq.jvm
  lazy val qqjs = qq.js

  def emptyInputTask: Def.Initialize[InputTask[Unit]] =
    InputTask.createDyn[String, Unit](
      InputTask.parserAsInput(
        sbt.complete.Parser.success("")
      ))(Def.task[String => Def.Initialize[Task[Unit]]] { (s: String) => Def.task(()) })

  private val disableTests: Seq[Def.Setting[_]] = Seq(
    test in Test := (),
    testQuick in Test <<= emptyInputTask,
    testOnly in Test <<= emptyInputTask
  )

  lazy val ui = Project(id = "ui", base = file("ui"))
    .dependsOn(qqjs)
    .settings(ScalaJSPlugin.projectSettings)
    .enablePlugins(ScalaJSPlugin)
    .settings(baseSettings)
    .settings(chromeTasks)
    .settings(jsSettings)
    .settings(commonDeps)
    .settings(uiDeps)
    .settings(disableTests)

  def dependOnChrome[T](taskKey: TaskKey[T]): Def.Setting[Task[T]] =
    taskKey <<= taskKey.dependsOn(chromeBuildFast in ui)

  lazy val uitests = Project(id = "uitests", base = file("uitests"))
    .dependsOn(ui)
    .settings(libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "2.35.0" % "test")
    .settings(baseSettings)
    .settings(commonDeps)
    .settings(dependOnChrome(testOptions in Test))
    .settings((testQuick in Test) := (test in Test).value)

  lazy val root: Project = Project(id = "root", base = file("."))
    .aggregate(ui, uitests, qqjvm, qqjs)
    .settings(Defaults.projectCore)
    .settings(baseSettings)
    .settings(ScalaJSPlugin.globalSettings)
    .settings(disableTests)

}
