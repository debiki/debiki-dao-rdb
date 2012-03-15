import sbt._
import sbt.Keys._

object DebikiDaoOracle extends Build {

  def here(f: String) = new java.io.File(f)

  lazy val submDaoOracle = (Project("root", file("."))
    dependsOn(
        submCore,
        submTckDao % "test"))

  lazy val submCore = Project("debiki-core", file("debiki-core"))
  lazy val submTckDao = (Project("tck-dao", file("debiki-tck-dao"))
    dependsOn(
        submCore))

  /*
  // This won't work, SBT says something like
  //  "[error] sbt.Dag$Cyclic: Cyclic reference involving
  //   sbt.Project$$anon$2@3580e2"
  lazy val submCore = RootProject(file("../debiki-core"))
  lazy val submTckDao = Project("tck-dao", here("../debiki-tck-dao"))

  // And this won't work, because SBT says:
  //  "Directory " + projectBase + " is not contained in build root"
  lazy val submTckDao = RootProject(file("../debiki-tck-dao"))
  */

  scalacOptions in ThisBuild ++= Seq("-deprecation")

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
