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
trait PostsReadStatsSiteDaoMixin extends SiteDbDao with SiteTransaction {
  self: RdbSiteDao =>


  def updatePostsReadStats(pageId: PageId, postIdsRead: Set[PostId],
        readById: UserId2, readFromIp: String) {

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
    for (postId <- postIdsRead) {
      transactionCheckQuota { implicit connection =>
        val sql = s"""
          insert into DW1_POSTS_READ_STATS(
            SITE_ID, PAGE_ID, POST_ID, IP, USER_ID, READ_AT)
          values (?, ?, ?, ?, ?, ?)"""
        val values = List[AnyRef](siteId, pageId, postId.asAnyRef,
          readFromIp, readById.toString, currentTime)  // UserId2
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


  def loadPostsReadStats(pageId: PageId): PostsReadStats = {
    val sql = s"""
      select POST_ID, IP, USER_ID from DW1_POSTS_READ_STATS
      where SITE_ID = ? and PAGE_ID = ?"""
    val values = List[AnyRef](siteId, pageId)
    val ipsByPostId = mutable.HashMap[PostId, ArrayBuffer[String]]()
    val roleIdsByPostId = mutable.HashMap[PostId, ArrayBuffer[RoleId]]()

    db.queryAtnms(sql, values, rs => {
      while (rs.next) {
        val postId = rs.getInt("POST_ID")
        val ip = rs.getString("IP")
        val anyUserId = Option(rs.getString("USER_ID"))
        anyUserId match {
          case Some(id) if User.isRoleId(id) =>
            val buffer = roleIdsByPostId.getOrElseUpdate(postId, new ArrayBuffer[RoleId])
            buffer += id
          case _ =>
            val buffer = ipsByPostId.getOrElseUpdate(postId, new ArrayBuffer[String])
            buffer += ip
        }
      }
    })

    val immutableIpsMap = ipsByPostId.map({ case (postId, ips) =>
      (postId, ips.toSet)
    }).toMap
    val immutableRolesMap = roleIdsByPostId.map({ case (postId, roleIds) =>
      (postId, roleIds.toSet)
    }).toMap

    PostsReadStats(immutableIpsMap, immutableRolesMap)
  }

}
