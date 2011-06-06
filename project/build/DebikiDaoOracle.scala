import sbt._

class DebikiDaoOracle(info: ProjectInfo) extends DefaultProject(info) {

  // Add Maven Local repository for SBT to search for
  val mavenLocal =
    "Local Maven Repository" at "file://"+Path.userHome+"/.m2/repository"

  // Dependencies
  val v = "2.8.1" // scala version
  val d79 = "net.liftweb" % ("lift-common_"+ v) % "2.2"
  val d52 = "net.liftweb" % ("lift-util_"+ v) % "2.2"
  val d95 = "junit" % "junit" % "4.7" % "test"
  // The test suite needs a sl4j implementation or it logs nothing on errors:
  val d86 = "ch.qos.logback" % "logback-classic" % "0.9.26" % "test"
  val d12 = "org.scala-tools.testing" % ("specs_"+ v) % "1.6.6" % "test"

  // Can I change this dependency to a *test* sub project?
  //val d83 = "com.debiki" % ("debiki-tck-dao") % "0.0.2-SNAPSHOT" % "test"
  // Something similar to this??
  // But can't get this to work, but the docs says it should work!?
  //override def deliverProjectDependencies =
  //  super.deliverProjectDependencies -
  //      submTckDao.projectID ++ Seq(submTckDao.projectID % "test->default")

  // Sub modules
  // COULD try to get rid of this silly compile time dependency
  // on debiki-tck-dao - this should be a compile-test dependency only.
  // Don't know how to declare *test* dependencies on subprojects though.
  lazy val submCore = project(Path.fromFile("../debiki-core"))
  lazy val submTckDao =
    project(Path.fromFile("../debiki-tck-dao"), "debiki-tck-dao",
      new DebikiTckDao(_), submCore)
    //project(Path.fromFile("../debiki-tck-dao"))

  class DebikiTckDao(info: ProjectInfo) extends DefaultProject(info) {
    val v = "2.8.1" // scala version
    val d79 = "net.liftweb" % ("lift-common_"+ v) % "2.2"
    val d52 = "net.liftweb" % ("lift-util_"+ v) % "2.2"
    val d95 = "junit" % "junit" % "4.7"
    val d12 = "org.scala-tools.testing" % ("specs_"+ v) % "1.6.6"
  }
}
