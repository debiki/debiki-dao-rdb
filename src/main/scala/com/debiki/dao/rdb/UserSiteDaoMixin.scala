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
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Loads info on users — should move some other methods that creates/updates
  * users from RdbSiteDao to this class too.
  */
trait UserSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def loadUserInfoAndStats(userId: UserId): Option[UserInfoAndStats] = {
    val user = loadUser(userId) getOrElse {
      return None
    }
    val stats = loadUserStats(userId)
    Some(UserInfoAndStats(user, stats))
  }


  def loadUserStats(userId: UserId): UserStats = {
    val (guestOrRoleId, userIdColumn) = RdbUtil.userIdAndColumnFor(userId)

    // The action that creates a new page should have id PageParts.BodyId,
    // so PAID = PageParts.BodyId means a new page was created.
    val query = s"""
      select TYPE, PAID = ${PageParts.BodyId} is_new_page, count(*) num
      from DW1_PAGE_ACTIONS
      where TENANT = ? and $userIdColumn = ?
      group by TYPE, is_new_page"""

    val values = List(siteId, guestOrRoleId)

    var numPages = 0
    var numPosts = 0
    var numLikesGiven = 0
    var numLikesReceived = 0
    var numWrongsGiven = 0
    var numWrongsReceived = 0
    var numOffTopicsGiven = 0
    var numOffTopicsReceived = 0

    db.queryAtnms(query, values, rs => {
      while (rs.next) {
        val num = rs.getInt("num")
        val isNewPage = rs.getBoolean("is_new_page")
        rs.getString("TYPE") match {
          case "Post" =>
            if (isNewPage) numPages = num
            else numPosts = num
          case "VoteLike" =>
            assErrIf(isNewPage, "DwE804F3")
            numLikesGiven = num
          case "VoteWrong" =>
            assErrIf(isNewPage, "DwE3G7k0")
            numWrongsGiven = num
          case "VoteOffTopic" =>
            assErrIf(isNewPage, "DwE53XA6")
            numOffTopicsGiven = num
          case _ =>
            // ignore, for now
        }
      }
    })

    UserStats(
      numPages = numPages,
      numPosts = numPosts,
      numReplies = 0, // not yet loaded
      numLikesGiven = numLikesGiven,
      numLikesReceived = 0, // not yet loaded
      numWrongsGiven = numWrongsGiven,
      numWrongsReceived = 0, // not yet loaded
      numOffTopicsGiven = numOffTopicsGiven,
      numOffTopicsReceived = 0) // not yet loaded
  }


  def listUserActions(userId: UserId): Seq[UserActionInfo] = {

    // Load incomplete action infos, which lack user display names, page
    // titles and page roles.
    val incompleteInfos: Seq[UserActionInfo] = loadIncompleteActionInfos(userId)

    // Load user display names.
    val userIds = incompleteInfos.map(_.actingUserId).distinct //++ actionInfos.map(_.targetUserId)
    val usersById = loadUsersAsMap(userIds)

    // Load page titles and roles.
    val pageIds = incompleteInfos.map(_.pageId).distinct
    val pageMetaById = loadPageMetas(pageIds)

    // Update the incomplete action infos.
    val infos = incompleteInfos flatMap { actionInfo =>
      pageMetaById.get(actionInfo.pageId).toList map { pageMeta =>
        val anyActingUser = usersById.get(actionInfo.actingUserId)
        actionInfo.copy(
          pageTitle = pageMeta.cachedTitle.getOrElse("(Unnamed page)"),
          pageRole = pageMeta.pageRole,
          actingUserDisplayName = anyActingUser.map(_.displayName) getOrElse "(Unnamed user)")
      }
    }
    infos
  }


  private def loadIncompleteActionInfos(userId: UserId): Seq[UserActionInfo] = {
    val (guestOrRoleId, userIdColumn) = RdbUtil.userIdAndColumnFor(userId)
    val query = s"""
      select PAGE_ID, POST_ID, PAID, TIME, TYPE, RELPA, TEXT, TIME
      from DW1_PAGE_ACTIONS
      where TENANT = ? and $userIdColumn = ?
      order by time desc
      limit 40"""
    val values = List[AnyRef](siteId, guestOrRoleId)

    val result = mutable.ArrayBuffer[UserActionInfo]()
    db.queryAtnms(query, values, rs => {
      while (rs.next) {
        val pageId = rs.getString("PAGE_ID")
        val postId = rs.getInt("POST_ID")
        val actionId = rs.getInt("PAID")
        val tyype = rs.getString("TYPE")
        val relatedActionId = rs.getInt("RELPA")
        var excerpt = Option(rs.getString("TEXT")) getOrElse ""
        val createdAt = ts2d(rs.getTimestamp("TIME"))

        var createdNewPage = false
        var repliedToPostId: Option[PostId] = None
        var editedPostId: Option[PostId] = None
        var votedLike = false
        var votedWrong = false
        var votedOffTopic = false

        tyype match {
          case "Post" =>
            if (actionId == PageParts.BodyId) createdNewPage = true
            else repliedToPostId = Some(relatedActionId)
          case "Edit" =>
            editedPostId = Some(postId)
            excerpt = "" // it's a diff, don't include
          case "VoteLike" => votedLike = true
          case "VoteWrong" => votedWrong = true
          case "VoteOffTopic" => votedOffTopic = true
          case _ => // ignore, for now
        }

        val actionInfo = UserActionInfo(
          userId = userId,
          pageId = pageId,
          pageTitle = "", // filled in later
          pageRole = PageRole.Generic, // filled in later
          postId = postId,
          postExcerpt = excerpt.take(200) + "...", // or load current version from DW1_POSTS
          actionId = actionId,
          actingUserId = userId,
          actingUserDisplayName = "?", // filled in later
          targetUserId = "?",
          targetUserDisplayName = "?", // filled in later (not yet implemented)
          createdAt = createdAt,
          createdNewPage = createdNewPage,
          repliedToPostId = repliedToPostId,
          editedPostId = editedPostId,
          votedLike = votedLike,
          votedWrong = votedWrong,
          votedOffTopic = votedOffTopic)

        result += actionInfo
      }
    })

    result
  }

}
