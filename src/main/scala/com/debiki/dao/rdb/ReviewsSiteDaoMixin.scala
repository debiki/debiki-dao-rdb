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
trait ReviewsSiteDaoMixin extends SiteDbDao with SiteTransaction {
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
        already_approved = ?,
        created_at = ?,
        more_reasons_at = ?,
        reviewed_at = ?,
        reviewed_by_id = ?,
        invalidated_at = ?,
        resolution = ?,
        user_id = ?,
        post_id = ?,
        revision_nr = ?
      where site_id = ? and id = ?
      """
    val updateValues = List[AnyRef](
      ReviewReason.toLong(reviewTask.reasons).asAnyRef,
      reviewTask.alreadyApproved.asAnyRef,
      reviewTask.createdAt,
      reviewTask.moreReasonsAt.orNullTimestamp,
      reviewTask.reviewedAt.orNullTimestamp,
      reviewTask.reviewedById.orNullInt,
      reviewTask.invalidatedAt.orNullTimestamp,
      reviewTask.resolution.orNullInt,
      reviewTask.userId.orNullInt,
      reviewTask.postId.orNullInt,
      reviewTask.revisionNr.orNullInt,
      siteId,
      reviewTask.id.asAnyRef)

    val found = runUpdateSingleRow(updateStatement, updateValues)
    if (found)
      return

    val statement = """
      insert into dw2_review_tasks(
        site_id,
        id,
        done_by_id,
        reasons,
        already_approved,
        created_at,
        more_reasons_at,
        reviewed_at,
        reviewed_by_id,
        invalidated_at,
        resolution,
        user_id,
        post_id,
        revision_nr)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    val values = List[AnyRef](
      siteId,
      reviewTask.id.asAnyRef,
      reviewTask.doneById.asAnyRef,
      ReviewReason.toLong(reviewTask.reasons).asAnyRef,
      reviewTask.alreadyApproved.asAnyRef,
      reviewTask.createdAt,
      reviewTask.moreReasonsAt.orNullTimestamp,
      reviewTask.reviewedAt.orNullTimestamp,
      reviewTask.reviewedById.orNullInt,
      reviewTask.invalidatedAt.orNullTimestamp,
      reviewTask.resolution.orNullInt,
      reviewTask.userId.orNullInt,
      reviewTask.postId.orNullInt,
      reviewTask.revisionNr.orNullInt)
    runUpdateSingleRow(statement, values)
  }


  override def loadPendingReviewTask(byUserId: UserId, postId: PostId): Option[ReviewTask] = {
    loadReviewTaskImpl(
      "done_by_id = ? and reviewed_at is null and post_id = ?",
      Seq(byUserId.asAnyRef, postId.asAnyRef))
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


  private def readReviewTask(rs: js.ResultSet): ReviewTask = {
    ReviewTask(
      id = rs.getInt("id"),
      doneById = rs.getInt("done_by_id"),
      reasons = ReviewReason.fromLong(rs.getLong("reasons")),
      alreadyApproved = rs.getBoolean("already_approved"),
      createdAt = getDate(rs, "created_at"),
      moreReasonsAt = getOptionalDate(rs, "more_reasons_at"),
      reviewedAt = getOptionalDate(rs, "reviewed_at"),
      reviewedById = getOptionalInt(rs, "reviewed_by_id"),
      invalidatedAt = getOptionalDate(rs, "invalidated_at"),
      resolution = getOptionalInt(rs, "resolution"),
      userId = getOptionalInt(rs, "user_id"),
      postId = getOptionalInt(rs, "post_id"),
      revisionNr = getOptionalInt(rs, "revision_nr"))
  }

}
