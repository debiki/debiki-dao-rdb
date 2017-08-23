name := "debiki-dao-pgsql"

organization := "com.debiki"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.12.3"

// You can download JDBC for Oracle 11.2.0.1 here:
// http://www.oracle.com/technetwork/database/enterprise-edition/
//   jdbc-112010-090769.html

libraryDependencies ++= Seq(
  "org.flywaydb" % "flyway-core" % "4.0.3")

// See: https://groups.google.com/forum/?fromgroups=#!topic/simple-build-tool/bkF1IDZj4L0
ideaPackagePrefix := None

// Makes `dependency-graph` work.
net.virtualvoid.sbt.graph.DependencyGraphSettings.graphSettings

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
