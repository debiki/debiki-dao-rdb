/**
 * Copyright (c) 2012-2013 Kaj Magnus Lindberg (born 1979)
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


