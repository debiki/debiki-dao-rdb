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


/** Constructs per site data access objects, and one global.
  */
class RelDbDaoFactory(val db: RelDb) extends DbDaoFactory {


  private val fullTextSearchIndexer = new FullTextSearchIndexer(this)

  val systemDbDao = new RelDbSystemDbDao(db, fullTextSearchIndexer)


  def newTenantDbDao(quotaConsumers: QuotaConsumers): TenantDbDao =
    new RelDbTenantDbDao(quotaConsumers, systemDbDao)


  /** Stops any background services started by this factory,
    * e.g. a full text search indexer.
    */
  def shutdown() {
    fullTextSearchIndexer.shutdown()
  }

}


