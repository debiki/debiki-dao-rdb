/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.v0

import akka.actor.ActorSystem
import com.jolbox.bonecp.BoneCPDataSource
import com.typesafe.config.{ConfigFactory, Config}
import Prelude._


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

    // Connect to test database.
    // (Load config settings from src/test/resources/application.conf, and from
    // <debiki-site-seed-repo>/conf-local/test-db.conf, included (softlinked) via
    // above application.conf.)
    val config: Config = ConfigFactory.load()

    val driverName = config.getString("db.test.driver")
    Class.forName(driverName);	// load driver
    val ds = new BoneCPDataSource()
    ds.setJdbcUrl(config.getString("db.test.url"))
    ds.setUsername(config.getString("db.test.user"))
    ds.setPassword(config.getString("db.test.password"))

    val db = new RelDb(ds)
    val daoFactory = new RelDbDaoFactory(db, ActorSystem("TestActorSystem"), isTest = true)

    // Prepare schema and search index.
    daoFactory.fullTextSearchIndexer.debugDeleteIndexAndMappings()
    daoFactory.fullTextSearchIndexer.createIndexAndMappinigsIfAbsent()
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


