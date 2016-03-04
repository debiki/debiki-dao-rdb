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
import Rdb._


/** Loads and saves ReviewTask:s.
  */
trait ReviewsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  override def nextReviewTaskId(): ReviewTaskId = {
    val query = """
      select max(id) max_id from dw2_review_tasks where site_id = ?
      """
    runQueryFindExactlyOne(query, List(siteId), rs => {
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  override def upsertReviewTask(reviewTask: ReviewTask) {
    // Later, with Postgres 9.5, use its built-in upsert.
    val updateStatement = """
      update dw2_review_tasks set
        reasons = ?,
        caused_by_id = ?,
        created_at = ?,
        created_at_rev_nr = ?,
        more_reasons_at = ?,
        completed_at = ?,
        completed_at_rev_nr = ?,
        completed_by_id = ?,
        invalidated_at = ?,
        resolution = ?,
        user_id = ?,
        page_id = ?,
        post_id = ?,
        post_nr = ?
      where site_id = ? and id = ?
      """
    val updateValues = List[AnyRef](
      ReviewReason.toLong(reviewTask.reasons).asAnyRef,
      reviewTask.causedById.asAnyRef,
      reviewTask.createdAt,
      reviewTask.createdAtRevNr.orNullInt,
      reviewTask.moreReasonsAt.orNullTimestamp,
      reviewTask.completedAt.orNullTimestamp,
      reviewTask.completedAtRevNr.orNullInt,
      reviewTask.completedById.orNullInt,
      reviewTask.invalidatedAt.orNullTimestamp,
      reviewTask.resolution.orNullInt,
      reviewTask.userId.orNullInt,
      reviewTask.pageId.orNullVarchar,
      reviewTask.postId.orNullInt,
      reviewTask.postNr.orNullInt,
      siteId,
      reviewTask.id.asAnyRef)

    val found = runUpdateSingleRow(updateStatement, updateValues)
    if (found)
      return

    val statement = """
      insert into dw2_review_tasks(
        site_id,
        id,
        reasons,
        caused_by_id,
        created_at,
        created_at_rev_nr,
        more_reasons_at,
        completed_at,
        completed_at_rev_nr,
        completed_by_id,
        invalidated_at,
        resolution,
        user_id,
        page_id,
        post_id,
        post_nr)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    val values = List[AnyRef](
      siteId,
      reviewTask.id.asAnyRef,
      ReviewReason.toLong(reviewTask.reasons).asAnyRef,
      reviewTask.causedById.asAnyRef,
      reviewTask.createdAt,
      reviewTask.createdAtRevNr.orNullInt,
      reviewTask.moreReasonsAt.orNullTimestamp,
      reviewTask.completedAt.orNullTimestamp,
      reviewTask.completedAtRevNr.orNullInt,
      reviewTask.completedById.orNullInt,
      reviewTask.invalidatedAt.orNullTimestamp,
      reviewTask.resolution.orNullInt,
      reviewTask.userId.orNullInt,
      reviewTask.pageId.orNullVarchar,
      reviewTask.postId.orNullInt,
      reviewTask.postNr.orNullInt)
    runUpdateSingleRow(statement, values)
  }


  override def loadPendingPostReviewTask(postId: UniquePostId): Option[ReviewTask] = {
    loadReviewTaskImpl(
      s"completed_at is null and invalidated_at is null and post_id = ?",
      Seq(postId.asAnyRef))
  }


  override def loadPendingPostReviewTask(postId: UniquePostId, causedById: UserId)
        : Option[ReviewTask] = {
    loadReviewTaskImpl(
      s"completed_at is null and invalidated_at is null and caused_by_id = ? and post_id = ?",
      Seq(causedById.asAnyRef, postId.asAnyRef))
  }


  override def loadReviewTask(id: ReviewTaskId): Option[ReviewTask] = {
    loadReviewTaskImpl("id = ?", List(id.asAnyRef))
  }


  private def loadReviewTaskImpl(whereClauses: String, values: Seq[AnyRef]): Option[ReviewTask] = {
    val query = i"""
      select * from dw2_review_tasks where site_id = ? and
      """ + whereClauses
    runQueryFindOneOrNone(query, (siteId +: values).toList, rs => {
      readReviewTask(rs)
    })
  }


  override def loadReviewTasks(olderOrEqualTo: ju.Date, limit: Int): Seq[ReviewTask] = {
    val query = i"""
      select * from dw2_review_tasks where site_id = ? and created_at < ?
      order by created_at desc limit ?
      """
    runQueryFindMany(query, List(siteId, olderOrEqualTo, limit.asAnyRef), rs => {
      readReviewTask(rs)
    })
  }


  override def loadReviewTaskCounts(isAdmin: Boolean): ReviewTaskCounts = {
    val urgentBits = ReviewReason.PostFlagged.toInt // + ... later if more urgent tasks
    val query = i"""
      select
        (select count(1) from dw2_review_tasks
          where site_id = ? and reasons & $urgentBits != 0 and resolution is null) num_urgent,
        (select count(1) from dw2_review_tasks
          where site_id = ? and reasons & $urgentBits = 0 and resolution is null) num_other
      """
    runQueryFindExactlyOne(query, List(siteId, siteId), rs => {
      ReviewTaskCounts(rs.getInt("num_urgent"), rs.getInt("num_other"))
    })
  }


  private def readReviewTask(rs: js.ResultSet): ReviewTask = {
    ReviewTask(
      id = rs.getInt("id"),
      reasons = ReviewReason.fromLong(rs.getLong("reasons")),
      causedById = rs.getInt("caused_by_id"),
      createdAt = getDate(rs, "created_at"),
      createdAtRevNr = getOptionalInt(rs, "created_at_rev_nr"),
      moreReasonsAt = getOptionalDate(rs, "more_reasons_at"),
      completedAt = getOptionalDate(rs, "completed_at"),
      completedAtRevNr = getOptionalInt(rs, "completed_at_rev_nr"),
      completedById = getOptionalInt(rs, "completed_by_id"),
      invalidatedAt = getOptionalDate(rs, "invalidated_at"),
      resolution = getOptionalInt(rs, "resolution"),
      userId = getOptionalInt(rs, "user_id"),
      pageId = Option(rs.getString("page_id")),
      postId = getOptionalInt(rs, "post_id"),
      postNr = getOptionalInt(rs, "post_nr"))
  }

}
