import sbt.*
import sbt.Keys.*

/** External library dependencies. */
object Dependencies:

  /** The version to use for each dependency. */
  object V:

    val sttpClient = "4.0.3"
    val circe      = "0.14.16"
    val cats       = "2.13.0"
    val catsEffect = "3.7.0"
    val munit      = "1.0.3"

  /**
    * Library dependencies associated with sttp client, for making HTTP requests
    * to the providers' APIs, with Cats Effect.
    */
  lazy val sttpClient = libraryDependencies ++= Seq(
    "com.softwaremill.sttp.client4" %% "core" % V.sttpClient,
    "com.softwaremill.sttp.client4" %% "cats" % V.sttpClient,
  )

  /** Library dependencies associated with Circe, for JSON parsing. */
  lazy val circe = libraryDependencies ++= Seq(
    "io.circe" %% "circe-core"   % V.circe,
    "io.circe" %% "circe-parser" % V.circe,
  )

  /** Library dependencies associated with cats, for FP abstractions. */
  lazy val cats = libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core"   % V.cats,
    "org.typelevel" %% "cats-effect" % V.catsEffect,
  )

  /** Library dependencies for testing with MUnit. */
  lazy val munit = libraryDependencies ++=
    Seq("org.scalameta" %% "munit" % V.munit % Test)
