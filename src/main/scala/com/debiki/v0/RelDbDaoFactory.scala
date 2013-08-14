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

package com.debiki.v0

import akka.actor.ActorSystem


/** Constructs per site data access objects, and one global.
  */
class RelDbDaoFactory(
  val db: RelDb,
  val actorSystem: ActorSystem,
  val isTest: Boolean = false) extends DbDaoFactory {


  val fullTextSearchIndexer = new FullTextSearchIndexer(this)

  val systemDbDao = new RelDbSystemDbDao(this)


  def newTenantDbDao(quotaConsumers: QuotaConsumers) =
    new RelDbTenantDbDao(quotaConsumers, this)


  /** Stops any background services started by this factory,
    * e.g. a full text search indexer.
    */
  def shutdown() {
    fullTextSearchIndexer.shutdown()
  }

  override def debugWaitUntilSearchEngineStarted() {
    fullTextSearchIndexer.debugWaitUntilSearchEngineStarted()
  }

  override def debugRefreshSearchEngineIndexer() {
    fullTextSearchIndexer.debugRefreshIndexes()
  }
}


