name := "debiki-dao-pgsql"

organization := "com.debiki"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.10.3"

// You can download JDBC for Oracle 11.2.0.1 here:
// http://www.oracle.com/technetwork/database/enterprise-edition/
//   jdbc-112010-090769.html

libraryDependencies ++= Seq(
  "org.flywaydb" % "flyway-core" % "3.0",
  "junit" % "junit" % "4.7" % "test",
  // The test suite needs a sl4j implementation or it logs nothing on errors:
  "ch.qos.logback" % "logback-classic" % "0.9.26" % "test",
  "org.specs2" %% "specs2" % "1.14" % "test",
  "org.scalatest" %% "scalatest" % "2.2.0" % "test",
  // For loading test database connection info.
  "com.typesafe" % "config" % "1.0.1" % "test",
  // A data source:
  ("com.jolbox" % "bonecp" % "0.7.1.RELEASE" % "test" notTransitive())
    .exclude("com.google.guava", "guava")
    .exclude("org.slf4j", "slf4j-api"),
  // Jsoup removes HTML tags from a string.
  "org.jsoup" % "jsoup" % "1.7.2",
  // Full text search engine:
  "org.elasticsearch" % "elasticsearch" % "0.90.2"
  // These 2 lines below might be useful, if in the future I ever make debiki-dao-rdb
  // itself apply evolutions to the db schema. Then a Play app is needed, and
  // Play's JDBC module.
  //"play" %% "play" % "2.1.3" % "test"
  //"play" %% "play-jdbc" % "2.1.3" % "test"
)

// See: https://groups.google.com/forum/?fromgroups=#!topic/simple-build-tool/bkF1IDZj4L0
ideaPackagePrefix := None

// Makes `dependency-graph` work.
net.virtualvoid.sbt.graph.Plugin.graphSettings

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
