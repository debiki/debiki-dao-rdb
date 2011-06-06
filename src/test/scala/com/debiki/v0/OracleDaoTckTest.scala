// vim: ts=2 sw=2 et
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.debiki.v0

import java.io.{File, FileNotFoundException}
import java.{util => ju}
import net.liftweb.common._
import org.specs._
import org.specs.specification.PendingUntilFixed
import Prelude._

/*
These test schemas are dropped and recreated (data pump import, impdp)
before each test:

DEBIKI_TEST_0
DEBIKI_TEST_0_0_2_EMPTY
DEBIKI_TEST_0_0_2_DATA

Password (for all users): "auto-dropped"
*/

class OracleTestContext(override val dao: OracleDao) extends tck.TestContext {
  val schema = dao.schema
  val db = dao.schema.oradb

  override def createRestorePoint() {
    unimplemented
  }

  override def revertToRestorePoint() {
    unimplemented
  }
}

object OracleDaoTckTest {
  val connStr = "jdbc:oracle:thin:@//192.168.0.120:1521/debiki.ex.com"

  def testContextBuilder(what: tck.DaoTckTest.What, version: String) = {
    import tck.DaoTckTest._
    val schema = (version, what) match {
      case ("0", EmptySchema) => "DEBIKI_TEST_0"
      case ("0.0.2", EmptyTables) => "DEBIKI_TEST_0_0_2_EMPTY"
      case ("0.0.2", TablesWithData) => "DEBIKI_TEST_0_0_2_DATA"
      case _ => assErr("Broken test suite")
    }
    val dao = OracleDao.connectAndUpgradeSchemaThrow(
                          connStr, schema, "auto-dropped")
    new OracleTestContext(dao)
  }

  val testContextBuilder2 =
    (what: tck.DaoTckTest.What, version: String) => {
      val dao = OracleDao.connectAndUpgradeSchemaThrow(
        connStr, "DEBIKI_TEST", "apabanan454")
      new OracleTestContext(dao)
    }
}

import OracleDaoTckTest._


class OracleDaoTckTest extends tck.DaoTckTest(testContextBuilder) {
  // Tests defined in parent class DaoTckTest.
}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list