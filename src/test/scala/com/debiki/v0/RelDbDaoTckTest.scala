/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import Prelude._

/*
All tables in these test schemas are cleared before each test:

DEBIKI_TEST_0
DEBIKI_TEST_0_0_2_EMPTY
DEBIKI_TEST_0_0_2_DATA

Password (for all users): "auto-dropped"
*/


/**
 * The test suite. Actual tests are defined in parent class DbDaoTckTest.
 */
class RelDbDaoTckSpec extends tck.DbDaoTckTest(ReDbDaoTckTest)



class RelDbTestContext(
  override val dbDaoFactory: DbDaoFactory,
  override val quotaManager: QuotaCharger)
  extends tck.TestContext



object ReDbDaoTckTest extends tck.TestContextBuilder {

  override def buildTestContext(what: tck.DbDaoTckTest.What, version: String) = {
    import tck.DbDaoTckTest._

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

    val db = new RelDb(server = server, port = port, database = database,
       user = schema, password = "auto-dropped")

    val daoFactory = new RelDbDaoFactory(db)

    // Prepare schema.
    (version, what) match {
      case ("0", EmptySchema) =>
        unimplemented // db.updateAtnms(RelDbTestSql.PurgeSchema)
      case ("0.0.2", EmptyTables) =>
        daoFactory.systemDbDao.emptyDatabase()
        case ("0.0.2", TablesWithData) =>
        case _ => assErr("Broken test suite")
      }

    // A simple quota charger, which never throws any OverQuotaException.
    val kindQuotaCharger = new QuotaCharger {
      override def chargeOrThrow(quotaConsumers: QuotaConsumers,
            resourceUse: ResourceUse, mayPilfer: Boolean) { }
      override def throwUnlessEnoughQuota(quotaConsumers: QuotaConsumers,
            resourceUse: ResourceUse, mayPilfer: Boolean) { }
    }

    new RelDbTestContext(daoFactory, kindQuotaCharger)
  }
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