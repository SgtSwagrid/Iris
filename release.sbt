ThisBuild / description :=
  "A provider-agnostic Scala client for large language models."

ThisBuild / homepage := Some(url("https://github.com/SgtSwagrid/Iris"))

ThisBuild / organization         := "com.alecdorrington"
ThisBuild / organizationName     := "SgtSwagrid"
ThisBuild / organizationHomepage := Some(url("https://github.com/SgtSwagrid"))

// Still in beta: anything may change between minor versions until 1.0.0.
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / licenses :=
  List("MIT" -> url("https://opensource.org/licenses/MIT"))

ThisBuild / developers := List(Developer(
  id = "SgtSwagrid",
  name = "Alec Dorrington",
  email = "alecdorrington@gmail.com",
  url = url("https://github.com/SgtSwagrid"),
))
