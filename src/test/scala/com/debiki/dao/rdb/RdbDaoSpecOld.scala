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

package com.debiki.dao.rdb

import com.debiki.core._
import com.debiki.core.Prelude._
import com.debiki.tck
import akka.actor.ActorSystem
import com.jolbox.bonecp.BoneCPDataSource
import com.typesafe.config.{ConfigFactory, Config}


/** The old Specs2 based test suite. Actual tests are defined in parent class
  *   com.debiki.tck.dao.old.DbDaoTckTest,
  * but don't add new tests there however, because it's a huge and monolithic. Instead add
  * tests to new/old files in com.debiki.tck.dao (but not ...tck.dao.old).
  *
  * Tables in the test schema are cleared before each test. (What? That means just once,
  * since there's just one single huge spec )-:)
  */
class RdbDaoSpecOld extends tck.dao.old.DbDaoTckTest(new tck.dao.old.TestContextBuilder {


  override def buildTestContext(what: tck.dao.old.DbDaoTckTest.What, version: String) = {
    val daoFactory = com.debiki.dao.rdb.RdbDaoSuite.daoFactory

    // Prepare schema and search index.
    daoFactory.fullTextSearchIndexer.debugDeleteIndexAndMappings()
    daoFactory.fullTextSearchIndexer.createIndexAndMappinigsIfAbsent()
    daoFactory.systemDbDao.emptyDatabase()

    new tck.dao.old.TestContext(daoFactory)
  }

})


