/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
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

import collection.immutable
import collection.mutable.ArrayBuffer
import com.debiki.core._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._
import PageSiteDaoMixin._


/** Loads pages and posts.
  */
trait PageSiteDaoMixin extends SiteDbDao with SiteTransaction {
  self: RdbSiteDao =>

  override def loadPost(pageId: PageId, postId: PostId): Option[Post2] =
    loadPostsOnPageImpl(pageId, postId = Some(postId), siteId = None).headOption


  override def loadPostsOnPage(pageId: PageId, siteId: Option[SiteId]): immutable.Seq[Post2] =
    loadPostsOnPageImpl(pageId, postId = None, siteId = None)


  def loadPostsOnPageImpl(pageId: PageId, postId: Option[PostId], siteId: Option[SiteId])
        : immutable.Seq[Post2] = {
    var query = "select * from DW2_POSTS where SITE_ID = ? and PAGE_ID = ?"
    val values = ArrayBuffer[AnyRef](siteId.getOrElse(this.siteId), pageId)
    postId foreach { id =>
      // WOULD simplify: remove this block, use loadPosts(Iterable[PagePostId]) instead.
      query += " and POST_ID = ?"
      values.append(id.asAnyRef)
    }
    var results = ArrayBuffer[Post2]()
    runQuery(query, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs, pageId = Some(pageId))
        results += post
      }
    })
    results.to[immutable.Seq]
  }


  def loadPosts(pagePostIds: Iterable[PagePostId]): immutable.Seq[Post2] = {
    if (pagePostIds.isEmpty)
      return Nil

    val values = ArrayBuffer[AnyRef](siteId)
    val queryBuilder = new StringBuilder(256, "select * from DW2_POSTS where SITE_ID = ? and (")
    for (pagePostId <- pagePostIds) {
      if (pagePostId != pagePostIds.head) {
        queryBuilder.append(" or ")
      }
      queryBuilder.append("(page_id = ? and post_id = ?)")
      values.append(pagePostId.pageId, pagePostId.postId.asAnyRef)
    }
    queryBuilder.append(")")

    var results = ArrayBuffer[Post2]()
    runQuery(queryBuilder.toString, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results.to[immutable.Seq]
  }


  def loadPostsToReview(): immutable.Seq[Post2] = {
    val flaggedPosts = loadPostsToReviewImpl("""
      deleted_status is null and
      num_pending_flags > 0
      """)
    val unapprovedPosts = loadPostsToReviewImpl("""
      deleted_status is null and
      num_pending_flags = 0 and
      (approved_version is null or approved_version < current_version)
      """)
    val postsWithSuggestions = loadPostsToReviewImpl("""
      deleted_status is null and
      num_pending_flags = 0 and
      approved_version = current_version and
      num_edit_suggestions > 0
      """)
    (flaggedPosts ++ unapprovedPosts ++ postsWithSuggestions).to[immutable.Seq]
  }


  private def loadPostsToReviewImpl(whereTests: String): ArrayBuffer[Post2] = {
    var query = s"select * from dw2_posts where site_id = ? and $whereTests"
    val values = List(siteId)
    var results = ArrayBuffer[Post2]()
    runQuery(query, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results
  }


  override def insertPost(post: Post2) {
    val statement = """
      insert into dw2_posts(
        site_id,
        page_id,
        post_id,
        parent_post_id,
        multireply,

        created_at,
        created_by_id,

        last_edited_at,
        last_edited_by_id,
        last_approved_edit_at,
        last_approved_edit_by_id,
        num_distinct_editors,

        safe_version,
        approved_source,
        approved_html_sanitized,
        approved_at,
        approved_by_id,
        approved_version,
        current_source_patch,
        current_version,

        collapsed_status,
        collapsed_at,
        collapsed_by_id,

        closed_status,
        closed_at,
        closed_by_id,

        hidden_at,
        hidden_by_id,
        hidden_reason,

        deleted_status,
        deleted_at,
        deleted_by_id,

        pinned_position,

        num_pending_flags,
        num_handled_flags,
        num_edit_suggestions,

        num_like_votes,
        num_wrong_votes,
        num_collapse_votes,
        num_times_read)

      values (
        ?, ?, ?, ?, ?,
        ?, ?,
        ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?,
        ?, ?, ?,
        ?, ?, ?, ?)"""

    val values = List[AnyRef](
      post.siteId, post.pageId, post.id.asAnyRef,
      post.parentId.orNullInt, toDbMultireply(post.multireplyPostIds),
      d2ts(post.createdAt), post.createdById.asAnyRef,
      o2ts(post.lastEditedAt), post.lastEditedById.orNullInt,
      o2ts(post.lastApprovedEditAt), post.lastApprovedEditById.orNullInt,
      post.numDistinctEditors.asAnyRef,

      post.safeVersion.orNullInt,
      post.approvedSource.orNullVarchar,
      post.approvedHtmlSanitized.orNullVarchar,
      o2ts(post.approvedAt),
      post.approvedById.orNullInt,
      post.approvedVersion.orNullInt,
      post.currentSourcePatch.orNullVarchar,
      post.currentVersion.asAnyRef,

      toCollapsedStatusString(post.collapsedStatus),
      o2ts(post.collapsedAt),
      post.collapsedById.orNullInt,

      toClosedStatusString(post.closedStatus),
      o2ts(post.closedAt),
      post.closedById.orNullInt,

      o2ts(post.hiddenAt),
      post.hiddenById.orNullInt,
      NullVarchar,

      toDeletedStatusString(post.deletedStatus),
      o2ts(post.deletedAt),
      post.deletedById.orNullInt,

      post.pinnedPosition.orNullInt,

      post.numPendingFlags.asAnyRef,
      post.numHandledFlags.asAnyRef,
      post.numPendingEditSuggestions.asAnyRef,

      post.numLikeVotes.asAnyRef,
      post.numWrongVotes.asAnyRef,
      post.numCollapseVotes.asAnyRef,
      post.numTimesRead.asAnyRef)

    runUpdate(statement, values)
  }


  def updatePost(post: Post2) {
    val statement = """
      update dw2_posts set
        parent_post_id = ?,
        multireply = ?,

        updated_at = now(),

        last_edited_at = ?,
        last_edited_by_id = ?,
        last_approved_edit_at = ?,
        last_approved_edit_by_id = ?,
        num_distinct_editors = ?,

        safe_version = ?,
        approved_source = ?,
        approved_html_sanitized = ?,
        approved_at = ?,
        approved_by_id = ?,
        approved_version = ?,
        current_source_patch = ?,
        current_version = ?,

        collapsed_status = ?,
        collapsed_at = ?,
        collapsed_by_id = ?,

        closed_status = ?,
        closed_at = ?,
        closed_by_id = ?,

        hidden_at = ?,
        hidden_by_id = ?,
        hidden_reason = ?,

        deleted_status = ?,
        deleted_at = ?,
        deleted_by_id = ?,

        pinned_position = ?,

        num_pending_flags = ?,
        num_handled_flags = ?,
        num_edit_suggestions = ?,

        num_like_votes = ?,
        num_wrong_votes = ?,
        num_collapse_votes = ?,
        num_times_read = ?

      where site_id = ? and page_id = ? and post_id = ?"""

    val values = List[AnyRef](
      post.parentId.orNullInt, toDbMultireply(post.multireplyPostIds),
      o2ts(post.lastEditedAt), post.lastEditedById.orNullInt,
      o2ts(post.lastApprovedEditAt), post.lastApprovedEditById.orNullInt,
      post.numDistinctEditors.asAnyRef,

      post.safeVersion.orNullInt,
      post.approvedSource.orNullVarchar,
      post.approvedHtmlSanitized.orNullVarchar,
      o2ts(post.approvedAt),
      post.approvedById.orNullInt,
      post.approvedVersion.orNullInt,
      post.currentSourcePatch.orNullVarchar,
      post.currentVersion.asAnyRef,

      toCollapsedStatusString(post.collapsedStatus),
      o2ts(post.collapsedAt),
      post.collapsedById.orNullInt,

      toClosedStatusString(post.closedStatus),
      o2ts(post.closedAt),
      post.closedById.orNullInt,

      o2ts(post.hiddenAt),
      post.hiddenById.orNullInt,
      NullVarchar,

      toDeletedStatusString(post.deletedStatus),
      o2ts(post.deletedAt),
      post.deletedById.orNullInt,

      post.pinnedPosition.orNullInt,

      post.numPendingFlags.asAnyRef,
      post.numHandledFlags.asAnyRef,
      post.numPendingEditSuggestions.asAnyRef,

      post.numLikeVotes.asAnyRef,
      post.numWrongVotes.asAnyRef,
      post.numCollapseVotes.asAnyRef,
      post.numTimesRead.asAnyRef,

      post.siteId, post.pageId, post.id.asAnyRef)

    runUpdate(statement, values)
  }


  def readPost(rs: js.ResultSet, pageId: Option[PageId] = None): Post2 = {
    Post2(
      siteId = siteId,
      pageId = pageId.getOrElse(rs.getString("PAGE_ID")),
      id = rs.getInt("POST_ID"),
      parentId = getResultSetIntOption(rs, "PARENT_POST_ID"),
      multireplyPostIds = fromDbMultireply(rs.getString("MULTIREPLY")),
      createdAt = ts2d(rs.getTimestamp("CREATED_AT")),
      createdById = rs.getInt("CREATED_BY_ID"),
      lastEditedAt = ts2o(rs.getTimestamp("LAST_EDITED_AT")),
      lastEditedById = getResultSetIntOption(rs, "LAST_EDITED_BY_ID"),
      lastApprovedEditAt = ts2o(rs.getTimestamp("LAST_APPROVED_EDIT_AT")),
      lastApprovedEditById = getResultSetIntOption(rs, "LAST_APPROVED_EDIT_BY_ID"),
      numDistinctEditors = rs.getInt("NUM_DISTINCT_EDITORS"),
      safeVersion = getResultSetIntOption(rs, "SAFE_VERSION"),
      approvedSource = Option(rs.getString("APPROVED_SOURCE")),
      approvedHtmlSanitized = Option(rs.getString("APPROVED_HTML_SANITIZED")),
      approvedAt = ts2o(rs.getTimestamp("APPROVED_AT")),
      approvedById = getResultSetIntOption(rs, "APPROVED_BY_ID"),
      approvedVersion = getResultSetIntOption(rs, "APPROVED_VERSION"),
      currentSourcePatch = Option(rs.getString("CURRENT_SOURCE_PATCH")),
      currentVersion = rs.getInt("CURRENT_VERSION"),
      collapsedStatus = fromCollapsedStatusString(rs.getString("COLLAPSED_STATUS")),
      collapsedAt = ts2o(rs.getTimestamp("COLLAPSED_AT")),
      collapsedById = getResultSetIntOption(rs, "COLLAPSED_BY_ID"),
      closedStatus = fromClosedStatusString(rs.getString("CLOSED_STATUS")),
      closedAt = ts2o(rs.getTimestamp("CLOSED_AT")),
      closedById = getResultSetIntOption(rs, "CLOSED_BY_ID"),
      hiddenAt = ts2o(rs.getTimestamp("HIDDEN_AT")),
      hiddenById = getResultSetIntOption(rs, "HIDDEN_BY_ID"),
      // later: hidden_reason
      deletedStatus = fromDeletedStatusString(rs.getString("DELETED_STATUS")),
      deletedAt = ts2o(rs.getTimestamp("DELETED_AT")),
      deletedById = getResultSetIntOption(rs, "DELETED_BY_ID"),
      pinnedPosition = getResultSetIntOption(rs, "PINNED_POSITION"),
      numPendingFlags = rs.getInt("NUM_PENDING_FLAGS"),
      numHandledFlags = rs.getInt("NUM_HANDLED_FLAGS"),
      numPendingEditSuggestions = rs.getInt("NUM_EDIT_SUGGESTIONS"),
      numLikeVotes = rs.getInt("NUM_LIKE_VOTES"),
      numWrongVotes = rs.getInt("NUM_WRONG_VOTES"),
      numCollapseVotes = rs.getInt("NUM_COLLAPSE_VOTES"),
      numTimesRead = rs.getInt("NUM_TIMES_READ"))
  }


  def deleteVote(pageId: PageId, postId: PostId, voteType: PostVoteType, voterId: UserId2) {
    val statement = """
      delete from dw2_post_actions
      where site_id = ? and page_id = ? and post_id = ? and type = ? and created_by_id = ?
      """
    val values = List[AnyRef](siteId, pageId, postId.asAnyRef, toActionTypeInt(voteType),
      voterId.asAnyRef)
    val numDeleted = runUpdate(statement, values)
    dieIf(numDeleted > 1, "DwE4YP24", s"Too many actions deleted: numDeleted = $numDeleted")

    if (numDeleted == 1) {
      updateVoteCount(pageId, postId, voteType, plusOrMinus = "-")
    }
  }


  def insertVote(pageId: PageId, postId: PostId, voteType: PostVoteType, voterId: UserId2,
        voterIp: IpAddress) {
    insertPostAction(pageId, postId, actionType = voteType, doerId = voterId)
    updateVoteCount(pageId, postId, voteType, plusOrMinus = "+")
  }


  def updateVoteCount(pageId: PageId, postId: PostId, voteType: PostVoteType, plusOrMinus: String) {
    val numVotesColumn = voteType match {
      case PostVoteType.Like => "num_like_votes"
      case PostVoteType.Wrong => "num_wrong_votes"
    }
    val statement = s"""
      update dw2_posts set $numVotesColumn = $numVotesColumn $plusOrMinus 1
      where site_id = ? and page_id = ? and post_id = ?
      """
    val values = List[AnyRef](siteId, pageId, postId.asAnyRef)
    val numUpdated = runUpdate(statement, values)
    dieIf(numUpdated != 1, "DwE94KF54", s"Error updating vote sum: numUpdated = $numUpdated")

    // TODO split e.g. num_like_votes into ..._total and ..._unique? And update num_times_read too,
    // but probably not from here.
  }


  def loadActionsByUserOnPage(userId: UserId2, pageId: PageId): immutable.Seq[PostAction2] = {
    var query = """
      select post_id, type, created_by_id
      from dw2_post_actions
      where site_id = ? and page_id = ? and created_by_id = ?
      """
    val values = List[AnyRef](siteId, pageId, userId.asAnyRef)
    var results = Vector[PostAction2]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val postAction = PostAction2(
          pageId = pageId,
          postId = rs.getInt("post_id"),
          doerId = userId,
          actionType = fromActionTypeInt(rs.getInt("type")))
        results :+= postAction
      }
    })
    results
  }


  def loadFlagsFor(postIds: immutable.Seq[PostId]): immutable.Seq[PostFlag] = {
    if (postIds.isEmpty)
      return Nil

    var query = s"""
      select page_id, post_id, type, created_by_id
      from dw2_post_actions
      where site_id = ?
        and post_id in (${ makeInListFor(postIds) })
        and type in ($FlagValueSpam, $FlagValueInapt, $FlagValueOther)
      """
    val values: List[AnyRef] = siteId :: postIds.map(_.asAnyRef).toList
    var results = Vector[PostFlag]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val postAction = PostFlag(
          pageId = rs.getString("page_id"),
          postId = rs.getInt("post_id"),
          flaggerId = rs.getInt("created_by_id"),
          flagType = fromActionTypeIntToFlagType(rs.getInt("type")))
        dieIf(!postAction.actionType.isInstanceOf[PostFlagType], "DwE2dpg4")
        results :+= postAction
      }
    })
    results
  }


  def insertFlag(pageId: PageId, postId: PostId, flagType: PostFlagType, flaggerId: UserId2) {
    insertPostAction(pageId, postId, actionType = flagType, doerId = flaggerId)
  }


  def clearFlags(pageId: PageId, postId: PostId, clearedById: UserId2) {
    var statement = s"""
      update dw2_post_actions
      set deleted_at = ?, deleted_by_id = ?, updated_at = now()
      where site_id = ? and page_id = ? and post_id = ?
      """
    val values = List(d2ts(currentTime), clearedById.asAnyRef, siteId, pageId, postId.asAnyRef)
    runUpdate(statement, values)
  }


  def insertPostAction(pageId: PageId, postId: PostId, actionType: PostActionType, doerId: UserId2) {
    val statement = """
      insert into dw2_post_actions(site_id, page_id, post_id, type, created_by_id,
          created_at, sub_id)
      values (?, ?, ?, ?, ?, ?, 1)
      """
    val values = List[AnyRef](siteId, pageId, postId.asAnyRef, toActionTypeInt(actionType),
      doerId.asAnyRef, currentTime)
    val numInserted =
      try { runUpdate(statement, values) }
      catch {
        case ex: js.SQLException if isUniqueConstrViolation(ex) =>
          throw DbDao.DuplicateVoteException
      }
    dieIf(numInserted != 1, "DwE9FKw2", s"Error inserting action: numInserted = $numInserted")
  }

}


object PageSiteDaoMixin {

  private val VoteValueLike = 41
  private val VoteValueWrong = 42
  private val FlagValueSpam = 51
  private val FlagValueInapt = 52
  private val FlagValueOther = 53


  def toCollapsedStatusString(collapsedStatus: Option[CollapsedStatus]): AnyRef =
    collapsedStatus match {
      case None => NullVarchar
      case Some(CollapsedStatus.PostCollapsed) => "P"
      case Some(CollapsedStatus.TreeCollapsed) => "T"
      case Some(CollapsedStatus.AncestorCollapsed) => "A"
    }

  def fromCollapsedStatusString(value: String): Option[CollapsedStatus] =
    value match {
      case null => None
      case "P" => Some(CollapsedStatus.PostCollapsed)
      case "T" => Some(CollapsedStatus.TreeCollapsed)
      case "A" => Some(CollapsedStatus.AncestorCollapsed)
    }


  def toClosedStatusString(closedStatus: Option[ClosedStatus]): AnyRef =
    closedStatus match {
      case None => NullVarchar
      case Some(ClosedStatus.TreeClosed) => "T"
      case Some(ClosedStatus.AncestorClosed) => "A"
    }

  def fromClosedStatusString(value: String): Option[ClosedStatus] =
    value match {
      case null => None
      case "T" => Some(ClosedStatus.TreeClosed)
      case "A" => Some(ClosedStatus.AncestorClosed)
    }


  def toDeletedStatusString(deletedStatus: Option[DeletedStatus]): AnyRef =
    deletedStatus match {
      case None => NullVarchar
      case Some(DeletedStatus.PostDeleted) => "P"
      case Some(DeletedStatus.TreeDeleted) => "T"
      case Some(DeletedStatus.AncestorDeleted) => "A"
    }

  def fromDeletedStatusString(value: String): Option[DeletedStatus] =
    value match {
      case null => None
      case "P" => Some(DeletedStatus.PostDeleted)
      case "T" => Some(DeletedStatus.TreeDeleted)
      case "A" => Some(DeletedStatus.AncestorDeleted)
    }


  def toActionTypeInt(actionType: PostActionType): AnyRef = (actionType match {
    case PostVoteType.Like => VoteValueLike
    case PostVoteType.Wrong => VoteValueWrong
    case PostFlagType.Spam => FlagValueSpam
    case PostFlagType.Inapt => FlagValueInapt
    case PostFlagType.Other => FlagValueOther
  }).asAnyRef


  def fromActionTypeInt(value: Int): PostActionType = value match {
    case VoteValueLike => PostVoteType.Like
    case VoteValueWrong => PostVoteType.Wrong
    case FlagValueSpam => PostFlagType.Spam
    case FlagValueInapt => PostFlagType.Inapt
    case FlagValueOther => PostFlagType.Other
  }

  def fromActionTypeIntToFlagType(value: Int): PostFlagType = {
    val tyype = fromActionTypeInt(value)
    require(tyype.isInstanceOf[PostFlagType], "DwE4GKP52")
    tyype.asInstanceOf[PostFlagType]
  }

}
