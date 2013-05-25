/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import Prelude._
import com.typesafe.config.{ConfigFactory, Config}


/**
 * The test suite. Actual tests are defined in parent class DbDaoTckTest.
 *
 * Tables in the test schema are cleared before each test.
 */
class RelDbDaoTckSpec extends tck.DbDaoTckTest(ReDbDaoTckTest)



class RelDbTestContext(
  override val dbDaoFactory: DbDaoFactory,
  override val quotaManager: QuotaCharger)
  extends tck.TestContext



object ReDbDaoTckTest extends tck.TestContextBuilder {

  override def buildTestContext(what: tck.DbDaoTckTest.What, version: String) = {

    // Connect to test database. (Load config settings from src/test/resources/application.conf.)
    val config: Config = ConfigFactory.load()
    val server = config.getString("test.debiki.pgsql.server")
    val port = config.getString("test.debiki.pgsql.port")
    val database = config.getString("test.debiki.pgsql.database")
    val user = config.getString("test.debiki.pgsql.user")
    val password = config.getString("test.debiki.pgsql.password")

    val db = new RelDb(server = server, port = port, database = database,
       user = user, password = password)

    val daoFactory = new RelDbDaoFactory(db)

    // Prepare schema.
    daoFactory.systemDbDao.emptyDatabase()

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