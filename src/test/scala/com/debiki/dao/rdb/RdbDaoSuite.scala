/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
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


/** The database test suite,  runs all database tests.
  *
  * Run like so:   test-only com.debiki.dao.rdb.RdbDaoSuite
  */
class RdbDaoSuite extends tck.dao.DbDaoSuite(RdbDaoSuite.daoFactory)



object RdbDaoSuite {


  lazy val daoFactory = makeDaoFactory(withSearchEngine = true)

  /** Starting the search engine takes long (10 seconds), need not do unless we're
    * actually testing it.
    */
  lazy val daoFactoryNoSearchEngine = makeDaoFactory(withSearchEngine = false)


  private def makeDaoFactory(withSearchEngine: Boolean) = {
    // Connect to the test database.
    // (Load config settings from src/test/resources/application.conf, and from
    // <debiki-site-seed-repo>/conf-local/test-db.conf, included (softlinked) via
    // above application.conf.)
    val config: Config = ConfigFactory.load()

    val driverName = config.getString("db.test.driver")
    Class.forName(driverName)	// load driver
    val ds = new BoneCPDataSource()
    ds.setJdbcUrl(config.getString("db.test.url"))
    ds.setUsername(config.getString("db.test.user"))
    ds.setPassword(config.getString("db.test.password"))

    val db = new Rdb(ds)
    val daoFactory = new RdbDaoFactory(db, ActorSystem("TestActorSystem"),
      fullTextSearchDbDataPath = Some("target/elasticsearch-data"), isTest = true,
      fastStartSkipSearch = !withSearchEngine)

    daoFactory
  }

}


/** Spec runners that allow you to run individual specs from debiki-tck-dao.
  *
  * Unfortunately I think there's no way to instead declare them in debiki-tck-dao,
  * rather than duplicating them in each debiki-dao-* module.
  * Fortunately placing them here instead is not error prone, just boring.
  */
package specs {

  import RdbDaoSuite._
  import tck.dao.DbDaoSpecShutdown
  import tck.dao.specs._


  /** In Play's console:   test-only com.debiki.dao.rdb.specs.NotificationsSpecRunner
    */
  class NotificationsSpecRunner extends NotificationsSpec(daoFactoryNoSearchEngine)
    with DbDaoSpecShutdown

  /** In Play's console:   test-only com.debiki.dao.rdb.specs.UserInfoSpecRunner
    */
  class UserInfoSpecRunner extends UserInfoSpec(daoFactoryNoSearchEngine) with DbDaoSpecShutdown

  /** In Play's console:   test-only com.debiki.dao.rdb.specs.UserPageSettingsSpecRunner
    */
  class UserPageSettingsSpecRunner extends UserPageSettingsSpec(daoFactoryNoSearchEngine)
    with DbDaoSpecShutdown

  /** In Play's console:   test-only com.debiki.dao.rdb.specs.UserPreferencesSpecRunner
    */
  class UserPreferencesSpecRunner extends UserPreferencesSpec(daoFactoryNoSearchEngine)
    with DbDaoSpecShutdown

  /** In Play's console:   test-only com.debiki.dao.rdb.specs.ListUsernamesSpecRunner
    */
  class ListUsernamesSpecRunner extends ListUsernamesSpec(daoFactoryNoSearchEngine)
    with DbDaoSpecShutdown

  /** In Play's console:   test-only com.debiki.dao.rdb.specs.PostsReadStatsSpecRunner
    */
  class PostsReadStatsSpecRunner extends PostsReadStatsSpec(daoFactoryNoSearchEngine)
    with DbDaoSpecShutdown

  /** In Play's console:   test-only com.debiki.dao.rdb.specs.VoteSpecRunner
    */
  class VoteSpecRunner extends VoteSpec(daoFactoryNoSearchEngine)
    with DbDaoSpecShutdown
}
