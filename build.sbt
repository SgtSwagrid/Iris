import IdeSettings.packagePrefix
import sbt._
import sbt.Keys._
import sbtunidoc.BaseUnidocPlugin.autoImport.*
import sbtunidoc.ScalaUnidocPlugin

// This build is developed as part of a larger private project,
// which includes it by reference and from which it is automatically synchronised.
// The project is named after the library, so that it doesn't clash with a host's own.

val scala3 = "3.8.4"

ThisBuild / scalaVersion := scala3

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-explain-types",
  "-explain-cyclic",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
)

/**
  * A provider-agnostic client for the APIs of large language models, with an
  * adapter for each supported provider. JVM only, as it makes its requests
  * through the JDK's own HTTP client.
  */
lazy val iris = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    name          := "iris",
    packagePrefix := "com.alecdorrington.iris",
    Dependencies.sttpClient,
    Dependencies.circe,
    Dependencies.cats,
    Dependencies.munit,
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Iris"),
  )
