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
import java.{sql => js}
import scala.collection.mutable.ArrayBuffer
import Rdb._


/** Saves metadata about file uploads. The files themselves are stored elsewhere, e.g.
  * in the filesystem or in Google Cloud Storage or Amazon S3 + a CDN.
  */
trait UploadsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  def insertUploadedFileMeta(uploadRef: UploadRef, sizeBytes: Int, dimensions: Option[(Int, Int)]) {
    // COULD use `insert ... on conflict do nothing` here once have upgraded to Postgres 9.5.
    val (width, height) = dimensions match {
      case Some((w, h)) => (w.asAnyRef, h.asAnyRef)
      case None => (NullInt, NullInt)
    }
    val statement = """
      insert into dw2_uploads(
        base_url, hash_path_suffix, original_hash_path_suffix,
        num_references, size_bytes, width, height, created_at, updated_at)
      select
        ?, ?, ?,
        ?, ?, ?, ?, now_utc(), now_utc()
      -- For now, until Postgres 9.5 which will support `insert ... on conflict do nothing`:
      -- (Race condition: another session might insert just after the select – or does
      -- the serializable isolation level prevent that?)
      where not exists (
        select 1 from dw2_uploads
        where base_url = ? and hash_path_suffix = ?)
      """
    val values = List(
      uploadRef.baseUrl, uploadRef.hashPathSuffix, uploadRef.hashPathSuffix,
      0.asAnyRef, sizeBytes.asAnyRef, width, height,
      uploadRef.baseUrl, uploadRef.hashPathSuffix)

    // No point in handling unique errors — the transaction would be broken even if we detect them.
    runUpdateSingleRow(statement, values)

    // There might have been refs to this upload already, for some weird reason.
    updateNumReferences(uploadRef)
  }


  def deleteUploadedFileMeta(uploadRef: UploadRef) {
    unimplemented("deleting uploaded file meta")
  }


  def insertUploadedFileReference(postId: UniquePostId, uploadRef: UploadRef,
        addedById: UserId) {
    // COULD use `insert ... on conflict do nothing` here once have upgraded to Postgres 9.5.
    // Then remove `where not exists`
    val statement = """
      insert into dw2_upload_refs(
        site_id, post_id, base_url, hash_path_suffix, added_by_id, added_at)
      select ?, ?, ?, ?, ?, now_utc()
      where not exists (
        select 1 from dw2_upload_refs
        where site_id = ? and post_id = ? and base_url = ? and hash_path_suffix = ?)
      """
    val values = List(
      siteId, postId.asAnyRef, uploadRef.baseUrl, uploadRef.hashPathSuffix, addedById.asAnyRef,
      siteId, postId.asAnyRef, uploadRef.baseUrl, uploadRef.hashPathSuffix)

    try runUpdateSingleRow(statement, values)
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex) || !uniqueConstrViolatedIs("dw2_uplpst__p", ex))
          throw ex
      // Else: all fine: this post links to the uploaded file already.
    }

    updateNumReferences(uploadRef)
  }


  def deleteUploadedFileReference(postId: UniquePostId, uploadRef: UploadRef): Boolean = {
    val statement = """
      delete from dw2_upload_refs
      where site_id = ? and post_id = ? and base_url = ? and hash_path_suffix = ?
      """
    val values = List(siteId, postId.asAnyRef, uploadRef.baseUrl,
      uploadRef.hashPathSuffix)
    val gone = runUpdateSingleRow(statement, values)
    updateNumReferences(uploadRef)
    gone
  }


  def loadUploadedFileReferences(postId: UniquePostId): Set[UploadRef] = {
    val query = """
      select * from dw2_upload_refs
      where site_id = ? and post_id = ?
      """
    val result = ArrayBuffer[UploadRef]()
    runQuery(query, List(siteId, postId.asAnyRef), rs => {
      while (rs.next) {
        result.append(UploadRef(
          baseUrl = rs.getString("base_url"),
          hashPathSuffix = rs.getString("hash_path_suffix")))
      }
    })
    result.toSet
  }


  private def updateNumReferences(uploadRef: UploadRef) {
    val statement = """
      update dw2_uploads set
        updated_at = now_utc(),
        num_references = (
          select count(*) from dw2_upload_refs where base_url = ? and hash_path_suffix = ?)
      where base_url = ? and hash_path_suffix = ?
      """
    val values = List(uploadRef.baseUrl, uploadRef.hashPathSuffix,
      uploadRef.baseUrl, uploadRef.hashPathSuffix)
    runUpdateSingleRow(statement, values)
  }
}



