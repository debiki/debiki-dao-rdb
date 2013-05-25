name := "debiki-dao-pgsql"

organization := "com.debiki"

version := "0.0.2-SNAPSHOT"

scalaVersion := "2.10.1"

// You can download JDBC for Oracle 11.2.0.1 here:
// http://www.oracle.com/technetwork/database/enterprise-edition/
//   jdbc-112010-090769.html

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.7" % "test",
  // The test suite needs a sl4j implementation or it logs nothing on errors:
  "ch.qos.logback" % "logback-classic" % "0.9.26" % "test",
  "org.specs2" %% "specs2" % "1.14" % "test",
  // For loading test database connection info.
  "com.typesafe" % "config" % "1.0.1" % "test"
)


// Make Specs2 ignore child specs. This ought to be placed in debiki-tck-dao but
// I don't know how to place it there *and* affect dependent projects.
testOptions := Seq(Tests.Filter(s =>
  s.endsWith("Spec") && !s.endsWith("ChildSpec")))


// See: https://groups.google.com/forum/?fromgroups=#!topic/simple-build-tool/bkF1IDZj4L0
ideaPackagePrefix := None

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
