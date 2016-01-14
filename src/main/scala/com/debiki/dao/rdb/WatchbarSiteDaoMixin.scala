/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
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
import play.api.libs.json.Json
import Rdb._


/** Loads and saves how each user's watchbar currently looks.
  */
trait WatchbarSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  override def saveWathchbar(userId: UserId, watchbar: Watchbar) {
    /*
    val updateStatement = """
      update watchbar_3 set content = ?
      where site_id = ? and user_id = ?
      """
    val updateValues = List[AnyRef](siteId, userId.asAnyRef, watchbar.json)
    val found = runUpdateSingleRow(updateStatement, updateValues)

    // (Race condition, fairly harmless: humans are single threaded. Nevertheless,
    // use upsert instead in Postgres 9.5.)
    if (!found) {
      val insertStatement = """
        insert into watchbar_3 (site_id, user_id, content)
        values (?, ?, ?)
        """
      val insertValues = List[AnyRef](siteId, userId.asAnyRef, watchbar.json)
      runUpdateSingleRow(insertStatement, insertValues)
    }
    */
  }


  override def loadWatchbar(userId: UserId): Option[Watchbar] = {
    /*
    val query = """
      select content from watchbar_3 where site_id = ? and user_id = ?
      """
    runQueryFindOneOrNone(query, List(siteId, userId.asAnyRef), rs => {
      new Watchbar(Json.parse(rs.getString("content")))
    })
    */
    ???
  }

}
