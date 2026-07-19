scalaVersion := "3.8.4"

enablePlugins(JavaAppPackaging)

val catsVersion = "2.12.0" // Or check latest
val catsEffectVersion = "3.5.4" // Or check latest
val fs2Version = "3.13.0" // Check for the latest release

lazy val root = rootProject
  .settings(
    name := "tark",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test,

      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version, // For network, file I/O, etc.
      "com.softwaremill.sttp.client3" %% "core" % "3.10.3",
      "com.softwaremill.sttp.client3" %% "circe" % "3.10.3",
      "com.softwaremill.sttp.client3" %% "cats" % "3.10.3", // For functional IO backend wrapper
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-fs2" % "3.10.3",
      "io.circe" %% "circe-generic" % "0.14.9",
       "org.jline" % "jline" % "3.30.6"
    )
  )
