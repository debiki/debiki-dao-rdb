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

class RelDbTestContext(val daoImpl: RelDbDaoSpi) extends tck.TestContext {
  val db = daoImpl.db

  override def createRestorePoint() {
    unimplemented
  }

  override def revertToRestorePoint() {
    unimplemented
  }

  def hasRefConstraints = true
}

object ReDbDaoTckTest {
  def testContextBuilder(what: tck.DaoTckTest.What, version: String) = {
    import tck.DaoTckTest._

    // Connect.
    //val connStr = "jdbc:postgresql://192.168.0.123:5432/debiki"
    val server = "192.168.0.123"
    val port = "5432"
    val database = "debiki"
    val schema = (version, what) match {
      // DO NOT CHANGE schema name. The schema is PURGED before each test!
      case ("0", EmptySchema) => "DEBIKI_TEST_0"
      // DO NOT CHANGE schema name. All tables are EMPTIED before each test!
      case ("0.0.2", EmptyTables) => "debiki_test_0_0_2_empty"
      case ("0.0.2", TablesWithData) => "DEBIKI_TEST_0_0_2_DATA"
      case _ => assErr("Broken test suite")
    }
    val dao =  new RelDbDaoSpi(new RelDb(
        server = server, port = port, database = database,
        user = schema, password = "auto-dropped"))

    // Prepare schema.
    (version, what) match {
      case ("0", EmptySchema) =>
        unimplemented // dao.db.updateAtnms(RelDbTestSql.PurgeSchema)
      case ("0.0.2", EmptyTables) =>
          dao.db.transaction { implicit connection => """
            delete from DW1_NOTFS_PAGE_ACTIONS
            delete from DW1_EMAILS_OUT
            delete from DW1_PAGE_RATINGS
            delete from DW1_PAGE_ACTIONS
            delete from DW1_PATHS
            delete from DW1_PAGE_PATHS
            delete from DW1_PAGES
            delete from DW1_IDS_SIMPLE_EMAIL
            delete from DW1_LOGINS
            delete from DW1_IDS_SIMPLE
            delete from DW1_IDS_OPENID
            delete from DW1_USERS
            delete from DW1_TENANT_HOSTS
            delete from DW1_TENANTS
            """.trim.split("\n") foreach { dao.db.update(_) }
            Empty // silly box
          }
        case ("0.0.2", TablesWithData) =>
        case _ => assErr("Broken test suite")
      }
    new RelDbTestContext(dao)
  }
}

class RelDbDaoTckTest extends tck.DaoTckTest(
              ReDbDaoTckTest.testContextBuilder) {
  // Tests defined in parent class DaoTckTest.
}

object OracleTestSql {
  val PurgeSchema = """
declare
  cursor c_constraints is
    select 'alter table '||table_name||' drop constraint '||
            constraint_name stmt
    from user_constraints;
  cursor c_tables is
    select 'drop table '|| table_name stmt
    from user_tables;
  cursor c_all is
    select 'drop '||object_type||' '|| object_name stmt
            -- || DECODE(OBJECT_TYPE,'TABLE',' CASCADE CONSTRAINTS;',';') stmt
    from user_objects;
begin
  for x in c_constraints loop
    execute immediate x.stmt;
  end loop;
  for x in c_tables loop
    execute immediate x.stmt;
  end loop;
  for x in c_all loop
    execute immediate x.stmt;
  end loop;
  -- execute immediate 'purge recyclebin'; -- drops som weird `LOB' objects.
     -- ^ Perhaps better skip this, in case I one day
     -- specify wrong schema to purge?
end;
  """
}
// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list