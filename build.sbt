name := "debiki-dao-pgsql"

organization := "com.debiki"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.11.7"

// You can download JDBC for Oracle 11.2.0.1 here:
// http://www.oracle.com/technetwork/database/enterprise-edition/
//   jdbc-112010-090769.html

libraryDependencies ++= Seq(
  "org.flywaydb" % "flyway-core" % "4.0.3",
  "junit" % "junit" % "4.7" % "test",
  // The test suite needs a sl4j implementation or it logs nothing on errors:
  "ch.qos.logback" % "logback-classic" % "0.9.26" % "test",
  "org.specs2" %% "specs2" % "2.3.12" % "test",
  "org.scalatest" %% "scalatest" % "2.2.0" % "test",
  // For loading test database connection info.
  "com.typesafe" % "config" % "1.0.1" % "test",
  // Jsoup removes HTML tags from a string.
  "org.jsoup" % "jsoup" % "1.7.2"
)

// See: https://groups.google.com/forum/?fromgroups=#!topic/simple-build-tool/bkF1IDZj4L0
ideaPackagePrefix := None

// Makes `dependency-graph` work.
net.virtualvoid.sbt.graph.DependencyGraphSettings.graphSettings

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
