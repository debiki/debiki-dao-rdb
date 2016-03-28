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

import collection.mutable
import collection.mutable.ArrayBuffer
import com.debiki.core._
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Saves and loads info on how many times each post has been read and by whom.
  */
trait PostsReadStatsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  def updatePostsReadStats(pageId: PageId, postNrsRead: Set[PostNr],
        readById: UserId, readFromIp: String) {

    // There's an ignore-duplicate-inserts rule in the database (DW1_PSTSRD_IGNORE_DUPL_INS).
    // However, if two transactions insert the same PK data at the same time that rule
    // will have no effect, see:
    //  http://postgresql.1045698.n5.nabble.com/Duplicated-entries-are-not-ignored-even-if-a-quot-do-instead-nothing-quot-rule-is-added-td5116004.html
    //   """if a concurrent transaction tries to create the same record, one of the transactions
    //   is going to find that it already exists on transaction commit. An INSERT-rule is not
    //   going to protect you against that."""
    // So let's insert each row in its own transaction and ignore any PK error.
    // (One single transaction for all rows won't work, because the whole transaction would
    // fail on any single unique key error.)
    // (If we'd like to avoid roundtrips for separate commits, we could enable autocommit?
    // Or insert via a stored procedure? Well, performance hardly matters.)
    for (postNr <- postNrsRead) {
      transactionCheckQuota { implicit connection =>
        val sql = s"""
          insert into post_read_stats3(
            SITE_ID, PAGE_ID, post_nr, IP, USER_ID, READ_AT)
          values (?, ?, ?, ?, ?, ?)"""
        val values = List[AnyRef](siteId, pageId, postNr.asAnyRef,
          readFromIp, readById.asAnyRef, currentTime)
        try {
          db.update(sql, values)
        }
        catch {
          case ex: js.SQLException if isUniqueConstrViolation(ex) =>
            // Ignore, simply means the user has already read the post.
            // And see the long comment above.
        }
      }
    }
  }


  def loadPostsReadStats(pageId: PageId): PostsReadStats =
    loadPostsReadStats(pageId, postNr = None)


  def loadPostsReadStats(pageId: PageId, postNr: Option[PostNr]): PostsReadStats = {
    var sql = s"""
      select post_nr, IP, USER_ID from post_read_stats3
      where SITE_ID = ? and PAGE_ID = ?"""
    val values = ArrayBuffer[AnyRef](siteId, pageId)
    postNr foreach { id =>
      sql += " and post_nr = ?"
      values.append(id.asAnyRef)
    }
    val ipsByPostNr = mutable.HashMap[PostNr, ArrayBuffer[String]]()
    val roleIdsByPostNr = mutable.HashMap[PostNr, ArrayBuffer[RoleId]]()

    runQuery(sql, values.toList, rs => {
      while (rs.next) {
        val postNr = rs.getInt("post_nr")
        val ip = rs.getString("IP")
        val anyUserId = getOptionalIntNoneNot0(rs, "USER_ID")
        anyUserId match {
          case Some(id) if User.isRoleId(id) =>
            val buffer = roleIdsByPostNr.getOrElseUpdate(postNr, new ArrayBuffer[RoleId])
            buffer += id
          case _ =>
            val buffer = ipsByPostNr.getOrElseUpdate(postNr, new ArrayBuffer[String])
            buffer += ip
        }
      }
    })

    val immutableIpsMap = ipsByPostNr.map({ case (postNr, ips) =>
      (postNr, ips.toSet)
    }).toMap
    val immutableRolesMap = roleIdsByPostNr.map({ case (postNr, roleIds) =>
      (postNr, roleIds.toSet)
    }).toMap

    PostsReadStats(immutableIpsMap, immutableRolesMap)
  }


  def movePostsReadStats(oldPageId: PageId, newPageId: PageId,
        newPostNrsByOldNrs: Map[PostNr, PostNr]) {
    require(oldPageId != newPageId, "EsE7YJK830")
    if (newPostNrsByOldNrs.isEmpty)
      return

    val oldPostNrs = newPostNrsByOldNrs.keys.toList
    val values = ArrayBuffer[AnyRef](newPageId)
    val whens: String = newPostNrsByOldNrs.toSeq.map(_ match {
      case (oldNr: PostNr, newNr: PostNr) =>
        values.append(oldNr.asAnyRef)
        values.append(newNr.asAnyRef)
        s"when ? then ?"
    }).mkString(" ")
    values.append(siteId)
    values.append(oldPageId)
    val statement = s"""
      update post_read_stats3 set
        page_id = ?,
        post_nr =
          case post_nr
          $whens
          else post_nr
          end
      where site_id = ? and page_id = ? and post_nr in (${makeInListFor(oldPostNrs)})
      """
    values.appendAll(oldPostNrs.map(_.asAnyRef))
    runUpdate(statement, values.toList)
  }

}
