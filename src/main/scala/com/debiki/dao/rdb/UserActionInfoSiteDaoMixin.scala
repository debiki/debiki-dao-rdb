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


/** Loads statistics on users, e.g. num posts posted, and lists things they've done.
  */
trait UserActionInfoSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def loadUserInfoAndStats(userId: UserId): Option[UserInfoAndStats] = {
    val user = loadUser(userId) getOrElse {
      return None
    }
    val stats = loadUserStats(userId)
    Some(UserInfoAndStats(user, stats))
  }


  def loadUserStats(userId: UserId): UserStats = {
    //unimplemented("Loading user statistics via new DW2... tables", "DwE4PIW5") /* wrong tables below
    /*val (guestOrRoleId, userIdColumn) = RdbUtil.userIdAndColumnFor(userId)

    // The action that creates a new page should have id PageParts.BodyId,
    // so PAID = PageParts.BodyId means a new page was created.
    val query = s"""
      select TYPE, PAID = ${PageParts.BodyId} is_new_page, count(*) num
      from DW1_PAGE_ACTIONS
      where SITE_ID = ? and $userIdColumn = ?
      group by TYPE, is_new_page"""

    val values = List(siteId, guestOrRoleId)
    */

    var numPages = 0
    var numPosts = 0
    var numLikesGiven = 0
    var numLikesReceived = 0
    var numWrongsGiven = 0
    var numWrongsReceived = 0
    var numBurysGiven = 0
    var numOffTopicsReceived = 0

    /*
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
    */

    UserStats(
      numPages = numPages,
      numPosts = numPosts,
      numReplies = 0, // not yet loaded
      numLikesGiven = numLikesGiven,
      numLikesReceived = 0, // not yet loaded
      numWrongsGiven = numWrongsGiven,
      numWrongsReceived = 0, // not yet loaded
      numBurysGiven = numBurysGiven,
      numBurysReceived = 0) // not yet loaded
  }


  def listUserActions(userId: UserId): Seq[UserActionInfo] = {
    unimplemented("Loading user actions [DwE6PKG74]") /*
    // Load incomplete action infos, which lack user display names, page
    // titles and page roles.
    val incompleteInfos: Seq[UserActionInfo] = loadIncompleteActionInfos(userId)

    // Load user display names.
    val userIds = incompleteInfos.map(_.actingUserId).distinct //++ actionInfos.map(_.targetUserId)
    val usersById = loadUsersAsMap(userIds)

    // Load page titles and roles.
    val pageIds = incompleteInfos.map(_.pageId).distinct
    val pageMetaById = loadPageMetasAsMap(pageIds)

    // Load excerpts (well, whole posts right now).
    val postsById: Map[PagePostId, PostState] = db.withConnection { connection =>
      unimplemented("Loading posts, DW1_POSTS gone", "DwE6PKG74") /* deleted: loadPostStatesById(
        incompleteInfos.map(info => PagePostId(pageId = info.pageId, postId = info.postId)))(
        connection)
        */
    }

    // Update the incomplete action infos.
    val infos = incompleteInfos flatMap { actionInfo =>
      pageMetaById.get(actionInfo.pageId).toList map { pageMeta =>
        val anyActingUser = usersById.get(actionInfo.actingUserId)
        val anyPostState = postsById.get(PagePostId(actionInfo.pageId, postId = actionInfo.postId))
        val anyExcerpt = anyPostState.flatMap(_.lastApprovedText) map { text =>
          if (text.length <= 200) text
          else text.take(197) + "..."
        }
        actionInfo.copy(
          pageTitle = "SHOULD load page title",
          pageRole = pageMeta.pageRole,
          postExcerpt = anyExcerpt.getOrElse("(Text not yet approved)"),
          actingUserDisplayName = anyActingUser.map(_.displayName) getOrElse "(Unnamed user)")
      }
    }
    infos
    */
  }


  private def loadIncompleteActionInfos(userId: UserId): Seq[UserActionInfo] = {
    unimplemented("Loading user action infos via new DW2... tables", "DwE6PK52") // wrong tbls below
    /*
    val (guestOrRoleId, userIdColumn) = RdbUtil.userIdAndColumnFor(userId)
    val query = s"""
      select PAGE_ID, POST_ID, PAID, TIME, TYPE, RELPA, TIME
      from DW1_PAGE_ACTIONS
      where SITE_ID = ? and $userIdColumn = ?
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
        val createdAt = ts2d(rs.getTimestamp("TIME"))

        var createdNewPage = false
        var repliedToPostId: Option[PostId] = None
        var editedPostId: Option[PostId] = None
        var votedLike = false
        var votedWrong = false
        var votedOffTopic = false
        var approved = false
        var deleted = false
        var pinned = false
        var collapsed = false
        var closed = false

        tyype match {
          case "Post" =>
            if (actionId == PageParts.BodyId) createdNewPage = true
            else repliedToPostId = Some(relatedActionId)
          case "Edit" =>
            editedPostId = Some(postId)
          case "VoteLike" => votedLike = true
          case "VoteWrong" => votedWrong = true
          case "VoteOffTopic" => votedOffTopic = true
          case "Aprv" => approved = true
          case "DelTree" | "DelPost" => deleted = true
          case "PinAtPos" | "PinVotes" => pinned = true
          case "CollapsePost" | "CollapseTree" => collapsed = true
          case "CloseTree" => closed = true
          case _ => // ignore, for now
        }

        val actionInfo = UserActionInfo(
          userId = userId,
          pageId = pageId,
          pageTitle = "", // filled in later
          pageRole = PageRole.WebPage, // filled in later
          postId = postId,
          postExcerpt = "?", // filled in later
          actionId = actionId,
          actingUserId = userId,
          actingUserDisplayName = "?", // filled in later
          targetUserId = "?",
          targetUserDisplayName = "?", // filled in later (not yet implemented)
          createdAt = createdAt,
          createdNewPage = createdNewPage,
          repliedToPostId = repliedToPostId,
          editedPostId = editedPostId,
          approved = approved,
          deleted = deleted,
          pinned = pinned,
          collapsed = collapsed,
          closed = closed,
          votedLike = votedLike,
          votedWrong = votedWrong,
          votedOffTopic = votedOffTopic)

        result += actionInfo
      }
    })

    result
    */
  }

}
