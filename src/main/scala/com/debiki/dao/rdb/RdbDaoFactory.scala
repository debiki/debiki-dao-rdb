/**
 * Copyright (C) 2011 Kaj Magnus Lindberg (born 1979)
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

import akka.actor.ActorSystem
import com.debiki.core._


/** Constructs per site data access objects, and one global.
  */
class RdbDaoFactory(
  val db: Rdb,
  val migrations: ScalaBasedDatabaseMigrations,
  val actorSystem: ActorSystem,
  val fullTextSearchDbDataPath: Option[String],
  val isTest: Boolean = false,
  val fastStartSkipSearch: Boolean = false) extends DbDaoFactory {


  def fullTextSearchIndexer =
    if (fastStartSkipSearch || true)
      sys.error("Search disabled, please check your Java -D...=... startup flags — no, always disabled right now, doesn't support dw2_posts")
    else
      _fullTextSearchIndexer

  private val _fullTextSearchIndexer =
    if (fastStartSkipSearch || true) null
    else new FullTextSearchIndexer(this)


  def newSiteDbDao(siteId: SiteId) =
    new RdbSiteDao(siteId, this)


  override def newSiteTransaction(siteId: SiteId, readOnly: Boolean, mustBeSerializable: Boolean)
      : SiteTransaction = {
    val dao = newSiteDbDao(siteId)
    dao.createTheOneAndOnlyConnection(readOnly = readOnly, mustBeSerializable = mustBeSerializable)
    dao
  }


  override def newSystemTransaction(readOnly: Boolean): SystemTransaction = {
    val dao = new RdbSystemDao(this)
    dao.createTheOneAndOnlyConnection(readOnly = readOnly)
    dao
  }


  /** Stops any background services started by this factory,
    * e.g. a full text search indexer.
    */
  def shutdown() {
    if (!fastStartSkipSearch) fullTextSearchIndexer.shutdown()
  }


  override def debugDeleteRecreateSearchEngineIndexes() {
    fullTextSearchIndexer.debugDeleteIndexAndMappings()
    fullTextSearchIndexer.createIndexAndMappinigsIfAbsent()
  }

  override def debugWaitUntilSearchEngineStarted() {
    fullTextSearchIndexer.debugWaitUntilSearchEngineStarted()
  }

  override def debugRefreshSearchEngineIndexer() {
    fullTextSearchIndexer.debugRefreshIndexes()
  }
}


