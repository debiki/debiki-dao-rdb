/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
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
import java.{sql => js, util => ju}
import scala.collection.immutable
import Rdb._


/** Loads and saves members of direct message conversations.
  */
trait PageUsersSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  override def insertMessageMember(pageId: PageId, userId: UserId, addedById: UserId): Boolean = {
    val statement = """
      insert into page_users3 (site_id, page_id, user_id, joined_by_id)
      values (?, ?, ?, ?)
      on conflict (site_id, page_id, user_id) do update set
        joined_by_id = excluded.joined_by_id,
        kicked_by_id = null
      """
    val values = List[AnyRef](siteId, pageId, userId.asAnyRef, addedById.asAnyRef)
    runUpdateSingleRow(statement, values)
  }


  override def removePageMember(pageId: PageId, userId: UserId, removedById: UserId): Boolean = {
    val statement = """
      update page_users3
      set kicked_by_id = ?
      where site_id = ? and page_id = ? and user_id = ?
      """
    val values = List[AnyRef](removedById.asAnyRef, siteId, pageId, userId.asAnyRef)
    runUpdateSingleRow(statement, values)
  }


  override def loadMessageMembers(pageId: PageId): Set[UserId] = {
    val query = """
      select user_id from page_users3
      where site_id = ?
        and page_id = ?
        and joined_by_id is not null
        and kicked_by_id is null
      """
    runQueryFindManyAsSet(query, List(siteId, pageId), rs => {
      rs.getInt("user_id")
    })
  }


  override def loadPageIdsUserIsMemberOf(userId: UserId, onlyPageRoles: Set[PageRole])
        : immutable.Seq[PageId] = {
    require(onlyPageRoles.nonEmpty, "EsE4G8U1")
    // Inline the page roles (rather than (?, ?, ?, ...)) because they'll always be the same
    // for each caller (hardcoded somewhere).
    val query = s"""
      select tu.page_id, p.page_role
      from page_users3 tu inner join pages3 p
        on tu.site_id = p.site_id and tu.page_id = p.page_id and p.page_role in (
            ${ onlyPageRoles.map(_.toInt).mkString(",") })
      where tu.site_id = ? and tu.user_id = ?
      order by p.last_reply_at desc
      """
    runQueryFindMany(query, List(siteId, userId.asAnyRef), rs => {
      rs.getString("page_id")
    })
  }

}
