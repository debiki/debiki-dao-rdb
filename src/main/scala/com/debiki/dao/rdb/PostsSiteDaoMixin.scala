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
import com.debiki.core.PageParts.TitleNr
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._
import PostsSiteDaoMixin._


/** Loads and saves posts.
  */
trait PostsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>

  override def loadPost(uniquePostId: UniquePostId): Option[Post] =
    loadPostsById(Seq(uniquePostId)).headOption


  override def loadPost(pageId: PageId, postNr: PostNr): Option[Post] =
    loadPostsOnPageImpl(pageId, postNr = Some(postNr), siteId = None).headOption


  override def loadPostsOnPage(pageId: PageId, siteId: Option[SiteId]): immutable.Seq[Post] =
    loadPostsOnPageImpl(pageId, postNr = None, siteId = None)


  def loadPostsOnPageImpl(pageId: PageId, postNr: Option[PostNr], siteId: Option[SiteId])
        : immutable.Seq[Post] = {
    var query = "select * from DW2_POSTS where SITE_ID = ? and PAGE_ID = ?"
    val values = ArrayBuffer[AnyRef](siteId.getOrElse(this.siteId), pageId)
    postNr foreach { id =>
      // WOULD simplify: remove this block, use loadPosts(Iterable[PagePostId]) instead.
      query += " and post_nr = ?"
      values.append(id.asAnyRef)
    }
    var results = ArrayBuffer[Post]()
    runQuery(query, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs, pageId = Some(pageId))
        results += post
      }
    })
    results.to[immutable.Seq]
  }


  private def loadPostsById(postIds: Iterable[UniquePostId]): immutable.Seq[Post] = {
    if (postIds.isEmpty)
      return Nil
    val values = ArrayBuffer[AnyRef](siteId)
    val queryBuilder = new StringBuilder(127, "select * from DW2_POSTS where SITE_ID = ? and (")
    var first = true
    for (postId <- postIds) {
      if (first) first = false
      else queryBuilder.append(" or ")
      queryBuilder.append("unique_post_id = ?")
      values.append(postId.asAnyRef)
    }
    queryBuilder.append(")")
    var results = ArrayBuffer[Post]()
    runQuery(queryBuilder.toString, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results.to[immutable.Seq]
  }


  def loadPosts(pagePostNrs: Iterable[PagePostNr]): immutable.Seq[Post] = {
    if (pagePostNrs.isEmpty)
      return Nil

    val values = ArrayBuffer[AnyRef](siteId)
    val queryBuilder = new StringBuilder(256, "select * from DW2_POSTS where SITE_ID = ? and (")
    var nr = 0
    for (pagePostNr: PagePostNr <- pagePostNrs.toSet) {
      if (nr >= 1) queryBuilder.append(" or ")
      nr += 1
      queryBuilder.append("(page_id = ? and post_nr = ?)")
      values.append(pagePostNr.pageId, pagePostNr.postNr.asAnyRef)
    }
    queryBuilder.append(")")

    var results = ArrayBuffer[Post]()
    runQuery(queryBuilder.toString, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results.to[immutable.Seq]
  }


  def loadPostsByUniqueId(postIds: Iterable[UniquePostId]): immutable.Map[UniquePostId, Post] = {
    if (postIds.isEmpty)
      return Map.empty

    val query = i"""
      select * from dw2_posts where site_id = ? and unique_post_id in (${makeInListFor(postIds)})
      """
    val values = siteId :: postIds.map(_.asAnyRef).toList
    runQueryBuildMap(query, values, rs => {
      val post = readPost(rs)
      post.uniqueId -> post
    })
  }


  def loadPostsBy(authorId: UserId, includeTitles: Boolean, limit: Int): immutable.Seq[Post] = {
    val andMaybeSkipTitles = includeTitles ? "" | s"and post_nr <> $TitleNr"
    val query = i"""
      select * from dw2_posts where site_id = ? and created_by_id = ? $andMaybeSkipTitles
      order by created_at desc limit ?
      """
    runQueryFindMany(query, List(siteId, authorId.asAnyRef, limit.asAnyRef), rs => {
      readPost(rs)
    })
  }


  def loadPostsToReview(): immutable.Seq[Post] = {
    val flaggedPosts = loadPostsToReviewImpl("""
      deleted_status = 0 and
      num_pending_flags > 0
      """)
    val unapprovedPosts = loadPostsToReviewImpl("""
      deleted_status = 0 and
      num_pending_flags = 0 and
      (approved_rev_nr is null or approved_rev_nr < curr_rev_nr)
      """)
    val postsWithSuggestions = loadPostsToReviewImpl("""
      deleted_status = 0 and
      num_pending_flags = 0 and
      approved_rev_nr = curr_rev_nr and
      num_edit_suggestions > 0
      """)
    (flaggedPosts ++ unapprovedPosts ++ postsWithSuggestions).to[immutable.Seq]
  }


  private def loadPostsToReviewImpl(whereTests: String): ArrayBuffer[Post] = {
    var query = s"select * from dw2_posts where site_id = ? and $whereTests"
    val values = List(siteId)
    var results = ArrayBuffer[Post]()
    runQuery(query, values.toList, rs => {
      while (rs.next()) {
        val post = readPost(rs)
        results += post
      }
    })
    results
  }


  override def nextPostId(): UniquePostId = {
    val query = """
      select max(unique_post_id) max_id from dw2_posts where site_id = ?
      """
    runQuery(query, List(siteId), rs => {
      rs.next()
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  override def insertPost(post: Post) {
    val statement = """
      insert into dw2_posts(
        site_id,
        unique_post_id,
        page_id,
        post_nr,
        parent_nr,
        multireply,
        type,

        created_at,
        created_by_id,

        curr_rev_started_at,
        curr_rev_by_id,
        curr_rev_last_edited_at,
        curr_rev_source_patch,
        curr_rev_nr,

        last_approved_edit_at,
        last_approved_edit_by_id,
        num_distinct_editors,

        safe_rev_nr,
        approved_source,
        approved_html_sanitized,
        approved_at,
        approved_by_id,
        approved_rev_nr,

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
        num_bury_votes,
        num_unwanted_votes,
        num_times_read)

      values (
        ?, ?, ?, ?, ?, ?, ?,
        ?, ?,
        ?, ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?, ?, ?,
        ?,
        ?, ?, ?,
        ?, ?, ?, ?, ?)"""

    val values = List[AnyRef](
      siteId, post.uniqueId.asAnyRef, post.pageId, post.nr.asAnyRef,
      post.parentNr.orNullInt, toDbMultireply(post.multireplyPostNrs),
      (post.tyype != PostType.Normal) ? post.tyype.toInt.asAnyRef | NullInt,

      d2ts(post.createdAt),
      post.createdById.asAnyRef,

      post.currentRevStaredAt,
      post.currentRevisionById.asAnyRef,
      post.currentRevLastEditedAt.orNullTimestamp,
      post.currentSourcePatch.orNullVarchar,
      post.currentRevisionNr.asAnyRef,

      o2ts(post.lastApprovedEditAt),
      post.lastApprovedEditById.orNullInt,
      post.numDistinctEditors.asAnyRef,

      post.safeRevisionNr.orNullInt,
      post.approvedSource.orNullVarchar,
      post.approvedHtmlSanitized.orNullVarchar,
      o2ts(post.approvedAt),
      post.approvedById.orNullInt,
      post.approvedRevisionNr.orNullInt,

      post.collapsedStatus.underlying.asAnyRef,
      o2ts(post.collapsedAt),
      post.collapsedById.orNullInt,

      post.closedStatus.underlying.asAnyRef,
      o2ts(post.closedAt),
      post.closedById.orNullInt,

      o2ts(post.hiddenAt),
      post.hiddenById.orNullInt,
      NullVarchar,

      post.deletedStatus.underlying.asAnyRef,
      o2ts(post.deletedAt),
      post.deletedById.orNullInt,

      post.pinnedPosition.orNullInt,

      post.numPendingFlags.asAnyRef,
      post.numHandledFlags.asAnyRef,
      post.numPendingEditSuggestions.asAnyRef,

      post.numLikeVotes.asAnyRef,
      post.numWrongVotes.asAnyRef,
      post.numBuryVotes.asAnyRef,
      post.numUnwantedVotes.asAnyRef,
      post.numTimesRead.asAnyRef)

    runUpdate(statement, values)
  }


  def updatePost(post: Post) {
    val statement = """
      update dw2_posts set
        parent_nr = ?,
        multireply = ?,
        type = ?,

        curr_rev_started_at = ?,
        curr_rev_by_id = ?,
        curr_rev_last_edited_at = ?,
        curr_rev_source_patch = ?,
        curr_rev_nr = ?,
        prev_rev_nr = ?,

        last_approved_edit_at = ?,
        last_approved_edit_by_id = ?,
        num_distinct_editors = ?,

        safe_rev_nr = ?,
        approved_source = ?,
        approved_html_sanitized = ?,
        approved_at = ?,
        approved_by_id = ?,
        approved_rev_nr = ?,

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
        num_bury_votes = ?,
        num_unwanted_votes = ?,
        num_times_read = ?

      where site_id = ? and page_id = ? and post_nr = ?"""

    val values = List[AnyRef](
      post.parentNr.orNullInt,
      toDbMultireply(post.multireplyPostNrs),
      (post.tyype != PostType.Normal) ? post.tyype.toInt.asAnyRef | NullInt,

      post.currentRevStaredAt,
      post.currentRevisionById.asAnyRef,
      post.currentRevLastEditedAt.orNullTimestamp,
      post.currentSourcePatch.orNullVarchar,
      post.currentRevisionNr.asAnyRef,
      post.previousRevisionNr.orNullInt,

      post.lastApprovedEditAt.orNullTimestamp,
      post.lastApprovedEditById.orNullInt,
      post.numDistinctEditors.asAnyRef,

      post.safeRevisionNr.orNullInt,
      post.approvedSource.orNullVarchar,
      post.approvedHtmlSanitized.orNullVarchar,
      o2ts(post.approvedAt),
      post.approvedById.orNullInt,
      post.approvedRevisionNr.orNullInt,

      post.collapsedStatus.underlying.asAnyRef,
      o2ts(post.collapsedAt),
      post.collapsedById.orNullInt,

      post.closedStatus.underlying.asAnyRef,
      o2ts(post.closedAt),
      post.closedById.orNullInt,

      o2ts(post.hiddenAt),
      post.hiddenById.orNullInt,
      NullVarchar,

      post.deletedStatus.underlying.asAnyRef,
      o2ts(post.deletedAt),
      post.deletedById.orNullInt,

      post.pinnedPosition.orNullInt,

      post.numPendingFlags.asAnyRef,
      post.numHandledFlags.asAnyRef,
      post.numPendingEditSuggestions.asAnyRef,

      post.numLikeVotes.asAnyRef,
      post.numWrongVotes.asAnyRef,
      post.numBuryVotes.asAnyRef,
      post.numUnwantedVotes.asAnyRef,
      post.numTimesRead.asAnyRef,

      siteId, post.pageId, post.nr.asAnyRef)

    runUpdate(statement, values)
  }


  private def readPost(rs: js.ResultSet, pageId: Option[PageId] = None): Post = {
    Post(
      uniqueId = rs.getInt("UNIQUE_POST_ID"),
      pageId = pageId.getOrElse(rs.getString("PAGE_ID")),
      nr = rs.getInt("post_nr"),
      parentNr = getOptionalInt(rs, "parent_nr"),
      multireplyPostNrs = fromDbMultireply(rs.getString("MULTIREPLY")),
      tyype = PostType.fromInt(rs.getInt("TYPE")).getOrElse(PostType.Normal),
      createdAt = getDate(rs, "CREATED_AT"),
      createdById = rs.getInt("CREATED_BY_ID"),
      currentRevStaredAt = getDate(rs, "curr_rev_started_at"),
      currentRevisionById = rs.getInt("curr_rev_by_id"),
      currentRevLastEditedAt = getOptionalDate(rs, "curr_rev_last_edited_at"),
      currentSourcePatch = Option(rs.getString("curr_rev_source_patch")),
      currentRevisionNr = rs.getInt("curr_rev_nr"),
      previousRevisionNr = getOptionalIntNoneNot0(rs, "prev_rev_nr"),
      lastApprovedEditAt = getOptionalDate(rs, "LAST_APPROVED_EDIT_AT"),
      lastApprovedEditById = getResultSetIntOption(rs, "LAST_APPROVED_EDIT_BY_ID"),
      numDistinctEditors = rs.getInt("NUM_DISTINCT_EDITORS"),
      safeRevisionNr = getResultSetIntOption(rs, "safe_rev_nr"),
      approvedSource = Option(rs.getString("APPROVED_SOURCE")),
      approvedHtmlSanitized = Option(rs.getString("APPROVED_HTML_SANITIZED")),
      approvedAt = getOptionalDate(rs, "APPROVED_AT"),
      approvedById = getResultSetIntOption(rs, "APPROVED_BY_ID"),
      approvedRevisionNr = getResultSetIntOption(rs, "approved_rev_nr"),
      collapsedStatus = new CollapsedStatus(rs.getInt("COLLAPSED_STATUS")),
      collapsedAt = getOptionalDate(rs, "COLLAPSED_AT"),
      collapsedById = getResultSetIntOption(rs, "COLLAPSED_BY_ID"),
      closedStatus = new ClosedStatus(rs.getInt("CLOSED_STATUS")),
      closedAt = getOptionalDate(rs, "CLOSED_AT"),
      closedById = getResultSetIntOption(rs, "CLOSED_BY_ID"),
      hiddenAt = getOptionalDate(rs, "HIDDEN_AT"),
      hiddenById = getResultSetIntOption(rs, "HIDDEN_BY_ID"),
      // later: hidden_reason
      deletedStatus = new DeletedStatus(rs.getInt("DELETED_STATUS")),
      deletedAt = getOptionalDate(rs, "DELETED_AT"),
      deletedById = getResultSetIntOption(rs, "DELETED_BY_ID"),
      pinnedPosition = getResultSetIntOption(rs, "PINNED_POSITION"),
      numPendingFlags = rs.getInt("NUM_PENDING_FLAGS"),
      numHandledFlags = rs.getInt("NUM_HANDLED_FLAGS"),
      numPendingEditSuggestions = rs.getInt("NUM_EDIT_SUGGESTIONS"),
      numLikeVotes = rs.getInt("NUM_LIKE_VOTES"),
      numWrongVotes = rs.getInt("NUM_WRONG_VOTES"),
      numBuryVotes = rs.getInt("NUM_BURY_VOTES"),
      numUnwantedVotes = rs.getInt("NUM_UNWANTED_VOTES"),
      numTimesRead = rs.getInt("NUM_TIMES_READ"))
  }


  def deleteVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId)
        : Boolean = {
    val statement = """
      delete from dw2_post_actions
      where site_id = ? and page_id = ? and post_nr = ? and type = ? and created_by_id = ?
      """
    val values = List[AnyRef](siteId, pageId, postNr.asAnyRef, toActionTypeInt(voteType),
      voterId.asAnyRef)
    val numDeleted = runUpdate(statement, values)
    dieIf(numDeleted > 1, "DwE4YP24", s"Too many actions deleted: numDeleted = $numDeleted")
    numDeleted == 1
  }


  def insertVote(uniquePostId: UniquePostId, pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId) {
    insertPostAction(uniquePostId, pageId, postNr, actionType = voteType, doerId = voterId)
  }


  def loadActionsByUserOnPage(userId: UserId, pageId: PageId): immutable.Seq[PostAction] = {
    var query = """
      select unique_post_id, post_nr, type, created_at, created_by_id
      from dw2_post_actions
      where site_id = ? and page_id = ? and created_by_id = ?
      """
    val values = List[AnyRef](siteId, pageId, userId.asAnyRef)
    var results = Vector[PostAction]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val postAction = PostAction(
          uniqueId = rs.getInt("unique_post_id"),
          pageId = pageId,
          postNr = rs.getInt("post_nr"),
          doneAt = getDate(rs, "created_at"),
          doerId = userId,
          actionType = fromActionTypeInt(rs.getInt("type")))
        results :+= postAction
      }
    })
    results
  }


  def loadActionsDoneToPost(pageId: PageId, postNr: PostNr): immutable.Seq[PostAction] = {
    var query = """
      select unique_post_id, type, created_at, created_by_id
      from dw2_post_actions
      where site_id = ? and page_id = ? and post_nr = ?
      """
    val values = List[AnyRef](siteId, pageId, postNr.asAnyRef)
    var results = Vector[PostAction]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val postAction = PostAction(
          uniqueId = rs.getInt("unique_post_id"),
          pageId = pageId,
          postNr = postNr,
          doneAt = getDate(rs, "created_at"),
          doerId = rs.getInt("created_by_id"),
          actionType = fromActionTypeInt(rs.getInt("type")))
        results :+= postAction
      }
    })
    results
  }


  def loadFlagsFor(pagePostNrs: immutable.Seq[PagePostNr]): immutable.Seq[PostFlag] = {
    if (pagePostNrs.isEmpty)
      return Nil

    val queryBuilder = new StringBuilder(256, s"""
      select unique_post_id, page_id, post_nr, type, created_at, created_by_id
      from dw2_post_actions
      where site_id = ?
        and type in ($FlagValueSpam, $FlagValueInapt, $FlagValueOther)
        and (
      """)
    val values = ArrayBuffer[AnyRef](siteId)
    var first = true
    pagePostNrs foreach { pagePostNr =>
      if (!first) {
        queryBuilder.append(" or ")
      }
      first = false
      queryBuilder.append("(page_id = ? and post_nr = ?)")
      values.append(pagePostNr.pageId, pagePostNr.postNr.asAnyRef)
    }
    queryBuilder.append(")")
    var results = Vector[PostFlag]()
    runQuery(queryBuilder.toString, values.toList, rs => {
      while (rs.next()) {
        val postAction = PostFlag(
          uniqueId = rs.getInt("unique_post_id"),
          pageId = rs.getString("page_id"),
          postNr = rs.getInt("post_nr"),
          flaggedAt = getDate(rs, "created_at"),
          flaggerId = rs.getInt("created_by_id"),
          flagType = fromActionTypeIntToFlagType(rs.getInt("type")))
        dieIf(!postAction.actionType.isInstanceOf[PostFlagType], "DwE2dpg4")
        results :+= postAction
      }
    })
    results
  }


  def insertFlag(uniquePostId: UniquePostId, pageId: PageId, postNr: PostNr, flagType: PostFlagType, flaggerId: UserId) {
    insertPostAction(uniquePostId, pageId, postNr, actionType = flagType, doerId = flaggerId)
  }


  def clearFlags(pageId: PageId, postNr: PostNr, clearedById: UserId) {
    var statement = s"""
      update dw2_post_actions
      set deleted_at = ?, deleted_by_id = ?, updated_at = now_utc()
      where site_id = ? and page_id = ? and post_nr = ? and deleted_at is null
      """
    val values = List(d2ts(currentTime), clearedById.asAnyRef, siteId, pageId, postNr.asAnyRef)
    runUpdate(statement, values)
  }


  def insertPostAction(uniquePostId: UniquePostId, pageId: PageId, postNr: PostNr, actionType: PostActionType, doerId: UserId) {
    val statement = """
      insert into dw2_post_actions(site_id, unique_post_id, page_id, post_nr, type, created_by_id,
          created_at, sub_id)
      values (?, ?, ?, ?, ?, ?, ?, 1)
      """
    val values = List[AnyRef](siteId, uniquePostId.asAnyRef, pageId, postNr.asAnyRef,
      toActionTypeInt(actionType), doerId.asAnyRef, currentTime)
    val numInserted =
      try { runUpdate(statement, values) }
      catch {
        case ex: js.SQLException if isUniqueConstrViolation(ex) =>
          throw DbDao.DuplicateVoteException
      }
    dieIf(numInserted != 1, "DwE9FKw2", s"Error inserting action: numInserted = $numInserted")
  }


  def loadLastPostRevision(postId: UniquePostId) =
    loadPostRevisionImpl(postId, PostRevision.LastRevisionMagicNr)


  def loadPostRevision(postId: UniquePostId, revisionNr: Int) =
    loadPostRevisionImpl(postId, revisionNr)


  private def loadPostRevisionImpl(postId: UniquePostId, revisionNr: Int): Option[PostRevision] = {
    var query = s"""
      select
        revision_nr, previous_nr, source_patch, full_source, title,
        composed_at, composed_by_id,
        approved_at, approved_by_id,
        hidden_at, hidden_by_id
      from dw2_post_revisions
      where site_id = ? and post_id = ? and revision_nr = """
    var values = List(siteId, postId.asAnyRef)

    if (revisionNr == PostRevision.LastRevisionMagicNr) {
      query += s"""(
        select max(revision_nr) from dw2_post_revisions
        where site_id = ? and post_id = ?
        )"""
      values = values ::: List(siteId, postId.asAnyRef)
    }
    else {
      query += "?"
      values = values ::: List(revisionNr.asAnyRef)
    }

    runQuery(query, values, rs => {
      if (!rs.next)
        return None

      Some(PostRevision(
        postId = postId,
        revisionNr = rs.getInt("revision_nr"),
        previousNr = getOptionalIntNoneNot0(rs, "previous_nr"),
        sourcePatch = Option(rs.getString("source_patch")),
        fullSource = Option(rs.getString("full_source")),
        title = Option(rs.getString("title")),
        composedAt = getDate(rs, "composed_at"),
        composedById = rs.getInt("composed_by_id"),
        approvedAt = getOptionalDate(rs, "approved_at"),
        approvedById = getOptionalIntNoneNot0(rs, "approved_by_id"),
        hiddenAt = getOptionalDate(rs, "hidden_at"),
        hiddenById = getOptionalIntNoneNot0(rs, "hidden_by_id")))
    })
  }


  def insertPostRevision(revision: PostRevision) {
    val statement = """
      insert into dw2_post_revisions(
        site_id, post_id,
        revision_nr, previous_nr,
        source_patch, full_source, title,
        composed_at, composed_by_id,
        approved_at, approved_by_id,
        hidden_at, hidden_by_id)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    val values = List[AnyRef](
      siteId, revision.postId.asAnyRef,
      revision.revisionNr.asAnyRef, revision.previousNr.orNullInt,
      revision.sourcePatch.orNullVarchar, revision.fullSource.orNullVarchar,
      revision.title.orNullVarchar,
      revision.composedAt, revision.composedById.asAnyRef,
      revision.approvedAt.orNullTimestamp, revision.approvedById.orNullInt,
      revision.hiddenAt.orNullTimestamp, revision.hiddenById.orNullInt)
    runUpdateExactlyOneRow(statement, values)
  }


  def updatePostRevision(revision: PostRevision) {
    UNTESTED
    val statement = """
      update dw2_post_revisions set
        source_patch = ?, full_source = ?, title = ?,
        composed_at = ?, combosed_by_id = ?,
        approved_at = ?, approved_by_id = ?,
        hidden_at = ?, hidden_by_id = ?
      where site_id = ? and post_id = ? and revision_nr = ?
      """
    val values = List[AnyRef](
      revision.sourcePatch.orNullVarchar, revision.fullSource.orNullVarchar,
      revision.title.orNullVarchar,
      revision.composedAt, revision.composedById.asAnyRef,
      revision.approvedAt.orNullTimestamp, revision.approvedById.orNullInt,
      revision.hiddenAt.orNullTimestamp, revision.hiddenById.orNullInt,
      siteId, revision.postId.asAnyRef, revision.revisionNr.asAnyRef)
    runUpdateExactlyOneRow(statement, values)
  }

}


object PostsSiteDaoMixin {

  private val VoteValueLike = 41
  private val VoteValueWrong = 42
  private val VoteValueBury = 43
  private val VoteValueUnwanted = 44
  private val FlagValueSpam = 51
  private val FlagValueInapt = 52
  private val FlagValueOther = 53


  def toActionTypeInt(actionType: PostActionType): AnyRef = (actionType match {
    case PostVoteType.Like => VoteValueLike
    case PostVoteType.Wrong => VoteValueWrong
    case PostVoteType.Bury => VoteValueBury
    case PostVoteType.Unwanted => VoteValueUnwanted
    case PostFlagType.Spam => FlagValueSpam
    case PostFlagType.Inapt => FlagValueInapt
    case PostFlagType.Other => FlagValueOther
  }).asAnyRef


  def fromActionTypeInt(value: Int): PostActionType = value match {
    case VoteValueLike => PostVoteType.Like
    case VoteValueWrong => PostVoteType.Wrong
    case VoteValueBury => PostVoteType.Bury
    case VoteValueUnwanted => PostVoteType.Unwanted
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
