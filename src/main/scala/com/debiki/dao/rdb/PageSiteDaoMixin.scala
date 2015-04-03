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
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Loads pages and posts.
  */
trait PageSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def savePageMeta(newMeta: PageMeta): Unit = ???


  override def loadPostsOnPage(pageId: PageId, siteId: Option[SiteId]): immutable.Seq[Post2] = {
    val query = "select * from DW2_POSTS where SITE_ID = ? and PAGE_ID = ?"
    val values = List[AnyRef](siteId.getOrElse(this.siteId), pageId)
    var results = ArrayBuffer[Post2]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val post = readPost(rs, pageId)
        results += post
      }
    })
    results.to[immutable.Seq]
  }


  override def saveNewPost(post: Post2) {
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
        ?, ?, ?, ?, ?, ?, ?,
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


  def saveEditedPost(post: Post2) {
    val statement = """
      update dw2_posts set
        parent_post_id = ?,
        multireply = ?,

        last_edited_at = ?,
        last_edited_by_id = ?,
        last_approved_edit_at = ?,
        last_approved_edit_by_id = ?,
        num_distinct_editors = ?,

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


  def readPost(rs: js.ResultSet, pageId: PageId): Post2 = {
    Post2(
      siteId = siteId,
      pageId = pageId,
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

}
