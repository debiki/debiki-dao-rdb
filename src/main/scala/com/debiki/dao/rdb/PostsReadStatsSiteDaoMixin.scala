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
trait PostsReadStatsSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def updatePostsReadStats(pageId: PageId, postIdsRead: Set[PostId],
        actionMakingThemRead: RawPostAction[_]) {
    db.transaction { implicit connection =>
      val sql = s"""
        insert into DW1_POSTS_READ_STATS(
          SITE_ID, PAGE_ID, POST_ID, USER_ID, READ_ACTION_ID, READ_AT)
        values (?, ?, ?, ?, ?, ?)"""
      for (postId <- postIdsRead) {
        val values = List[AnyRef](siteId, pageId, postId.asAnyRef,
          actionMakingThemRead.userId, actionMakingThemRead.id.asAnyRef,
          actionMakingThemRead.creationDati)
        // There's an ignore-duplicate-inserts rule in the database (DW1_PSTSRD_IGNORE_DUPL_INS).
        db.update(sql, values)
      }
    }
  }


  def loadPostsReadStats(pageId: PageId): PostsReadStats = {
    val sql = s"""
      select POST_ID, USER_ID from DW1_POSTS_READ_STATS
      where SITE_ID = ? and PAGE_ID = ?"""
    val values = List[AnyRef](siteId, pageId)
    val readerIdsByPostId = mutable.HashMap[PostId, ArrayBuffer[UserId]]()
    db.queryAtnms(sql, values, rs => {
      while (rs.next) {
        val postId = rs.getInt("POST_ID")
        val userId = rs.getString("USER_ID")
        val buffer = readerIdsByPostId.get(postId).getOrElse(new ArrayBuffer)
        buffer += userId
        readerIdsByPostId.put(postId, buffer)
      }
    })

    val immutableMap = readerIdsByPostId.map({ postAndUsers =>
      (postAndUsers._1, postAndUsers._2.toSet)
    }).toMap
    PostsReadStats(pageId, immutableMap)
  }

}
