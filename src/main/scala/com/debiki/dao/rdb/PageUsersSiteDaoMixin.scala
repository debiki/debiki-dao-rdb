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
  self: RdbSiteTransaction =>


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


  def loadReadProgress(userId: UserId, pageId: PageId): Option[ReadingProgress] = {
    val query = """
      select
        num_seconds_reading,
        first_visited_at_mins,
        last_visited_at_mins,
        last_viewed_post_nr,
        last_read_at_mins,
        last_read_post_nr,
        recently_read_nrs,
        low_post_nrs_read
      from page_users3
      where site_id = ?
        and page_id = ?
        and user_id = ?
      """
    runQueryFindOneOrNone(query, List(siteId, pageId, userId.asAnyRef), rs => {
      val firstVisitedAt = getWhenMinutes(rs, "first_visited_at_mins")
      if (rs.wasNull) {
        // There's a row for this user, although hen hasn't visited the page — apparently
        // someone else has made hen a page member, e.g. added hen to a chat channel.
        return None
      }

      // This is the very last post nr read.
      val lastReadPostNr = rs.getInt("last_read_post_nr")
      // These bits store even more recently read posts: the 2nd, 3rd, 4th, ... most recent ones.
      val recentlyReadNrsBytes: Array[Byte] =
        Option(rs.getBytes("recently_read_nrs")) getOrElse Array.empty

      // (For now, skip the bytes, not impl anywhere.)
      // *Add test* when implementing: [7GPKW205]
      val lastPostNrsRead =
        if (lastReadPostNr != 0) Vector(lastReadPostNr)
        else Vector.empty

      val lowPostNrsReadBytes: Array[Byte] =
        Option(rs.getBytes("low_post_nrs_read")) getOrElse Array.empty
      val lowPostNrsRead = ReadingProgress.parseLowPostNrsReadBitsetBytes(lowPostNrsReadBytes)

      ReadingProgress(
        firstVisitedAt = firstVisitedAt,
        lastVisitedAt = getWhenMinutes(rs, "last_visited_at_mins"),
        lastViewedPostNr = rs.getInt("last_viewed_post_nr"),
        lastReadAt = getOptWhenMinutes(rs, "last_read_at_mins"),
        lastPostNrsReadRecentFirst = lastPostNrsRead,
        lowPostNrsRead = lowPostNrsRead,
        secondsReading = rs.getInt("num_seconds_reading"))
    })
  }


  def upsertReadProgress(userId: UserId, pageId: PageId, progress: ReadingProgress) {
    val statement = """
      insert into page_users3 (
        site_id,
        page_id,
        user_id,
        num_seconds_reading,
        num_low_posts_read,
        first_visited_at_mins,
        last_visited_at_mins,
        last_viewed_post_nr,
        last_read_at_mins,
        last_read_post_nr,
        recently_read_nrs,
        low_post_nrs_read)
      values (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (site_id, page_id, user_id) do update set
        num_seconds_reading = excluded.num_seconds_reading,
        num_low_posts_read = excluded.num_low_posts_read,
        first_visited_at_mins = excluded.first_visited_at_mins,
        last_visited_at_mins = excluded.last_visited_at_mins,
        last_viewed_post_nr = excluded.last_viewed_post_nr,
        last_read_at_mins = excluded.last_read_at_mins,
        last_read_post_nr = excluded.last_read_post_nr,
        recently_read_nrs = excluded.recently_read_nrs,
        low_post_nrs_read = excluded.low_post_nrs_read
      """

    val values = List(
      siteId,
      pageId,
      userId.asAnyRef,
      progress.secondsReading.asAnyRef,
      progress.lowPostNrsRead.size.asAnyRef,
      progress.firstVisitedAt.unixMinutes.asAnyRef,
      progress.lastVisitedAt.unixMinutes.asAnyRef,
      progress.lastViewedPostNr.asAnyRef,
      progress.lastReadAt.map(_.unixMinutes).orNullInt,
      progress.lastPostNrsReadRecentFirst.headOption.orNullInt,
      NullBytea, // for now
      progress.lowPostNrsReadAsBitsetBytes.orNullByteaIfEmpty)

    runUpdateExactlyOneRow(statement, values)
  }

}
