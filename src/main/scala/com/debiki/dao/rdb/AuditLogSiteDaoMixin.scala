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
import com.debiki.core._
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Inserts, updates, loads audit log entries.
  */
trait AuditLogSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>

  var batchId: Option[AuditLogEntryId] = None
  var batchOffset = 0

  def startAuditLogBatch() {
    batchId = None
    val (id, _) = nextAuditLogEntryId()
    batchId = Some(id)
    batchOffset = 0
  }


  def nextAuditLogEntryId(): (AuditLogEntryId, Option[AuditLogEntryId]) = {
    batchId foreach { id =>
      val result = (id + batchOffset, batchId)
      batchOffset += 1
      return result
    }
    val query = "select max(audit_id) max_id from audit_log3 where site_id = ?"
    runQuery(query, List(siteId), rs => {
      rs.next()
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      (maxId + 1, None)
    })
  }


  def insertAuditLogEntry(entryNoId: AuditLogEntry) {
    val entry =
      if (entryNoId.id != AuditLogEntry.UnassignedId) entryNoId
      else {
        val (id, batchId) = nextAuditLogEntryId()
        entryNoId.copy(id = id, batchId = batchId)
      }

    require(entry.id >= 1, "DwE0GMF3")
    require(!entry.batchId.exists(_ > entry.id), "EsE4GGX2")
    require(entry.siteId == siteId, "DwE1FWU6")
    val statement = s"""
      insert into audit_log3(
        site_id,
        audit_id,
        batch_id,
        doer_id,
        done_at,
        did_what,
        details,
        email_address,
        ip,
        browser_id_cookie,
        browser_fingerprint,
        anonymity_network,
        country,
        region,
        city,
        page_id,
        page_role,
        post_id,
        post_nr,
        post_action_type,
        post_action_sub_id,
        upload_hash_path,
        upload_file_name,
        size_bytes,
        target_page_id,
        target_post_id,
        target_post_nr,
        target_user_id,
        target_site_id)
      values (
        ?, ?, ?, ?, ? at time zone 'UTC',
        ?, ?, ?, ?::inet,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """

    val values = List[AnyRef](
      entry.siteId,
      entry.id.asAnyRef,
      entry.batchId.orNullInt,
      entry.doerId.asAnyRef,
      entry.doneAt.asTimestamp,
      entry.didWhat.toInt.asAnyRef,
      NullVarchar,
      entry.emailAddress.orNullVarchar,
      entry.browserIdData.ip,
      entry.browserIdData.idCookie,
      entry.browserIdData.fingerprint.asAnyRef,
      NullVarchar,
      NullVarchar,
      NullVarchar,
      NullVarchar,
      entry.pageId.orNullVarchar,
      entry.pageRole.map(_.toInt).orNullInt,
      entry.uniquePostId.orNullInt,
      entry.postNr.orNullInt,
      NullInt,
      NullInt,
      entry.uploadHashPathSuffix.orNullVarchar,
      entry.uploadFileName.orNullVarchar,
      entry.sizeBytes.orNullInt,
      entry.targetPageId.orNullVarchar,
      entry.targetUniquePostId.orNullInt,
      entry.targetPostNr.orNullInt,
      entry.targetUserId.orNullInt,
      entry.targetSiteId.orNullVarchar)

    runUpdateSingleRow(statement, values)
  }


  def loadAuditLogEntriesRecentFirst(userId: UserId, tyype: AuditLogEntryType, limit: Int)
        : immutable.Seq[AuditLogEntry] = {
    val query = s"""
      select * from audit_log3
      where site_id = ? and doer_id = ?
      and did_what = ?
      order by done_at desc limit $limit
      """
    runQueryFindMany(query, List(siteId, userId.asAnyRef, tyype.toInt.asAnyRef), rs => {
      getAuditLogEntry(rs)
    })
  }


  def loadCreatePostAuditLogEntry(postId: UniquePostId): Option[AuditLogEntry] = {
    val query = s"""
      select * from audit_log3
      where site_id = ? and post_id = ?
      and did_what = ${AuditLogEntryType.NewPost.toInt}
      order by done_at limit 1
      """
    runQueryFindOneOrNone(query, List(siteId, postId.asAnyRef), rs => {
      getAuditLogEntry(rs)
    })
  }


  def loadCreatePostAuditLogEntriesBy(browserIdData: BrowserIdData, limit: Int, orderBy: OrderBy)
        : Seq[AuditLogEntry] = {
    UNTESTED
    dieIf(orderBy != OrderBy.MostRecentFirst, "EdE5PKB20", "Unimpl")
    val query = s"""
      select * from audit_log3
      where site_id = ? and ip = ?
      and did_what = ${AuditLogEntryType.NewPost.toInt}
      order by done_at desc limit $limit
      union
      select * from audit_log3
      where site_id = ? and browser_id_cookie = ?
      and did_what = ${AuditLogEntryType.NewPost.toInt}
      order by done_at desc limit $limit
      """
    val values = List(siteId, browserIdData.ip, siteId, browserIdData.idCookie)
    val entries = runQueryFindMany(query, values, rs => {
      getAuditLogEntry(rs)
    })
    entries.sortBy(-_.doneAt.getTime)
  }


  private def getAuditLogEntry(rs: js.ResultSet) =
    AuditLogEntry(
      siteId = siteId,
      id = rs.getInt("audit_id"),
      batchId = getOptionalInt(rs, "audit_id"),
      didWhat = AuditLogEntryType.fromInt(rs.getInt("did_what")) getOrDie "EsE7YKG83",
      doerId = rs.getInt("doer_id"),
      doneAt = getDate(rs, "done_at"),
      emailAddress = Option(rs.getString("email_address")),
      browserIdData = getBrowserIdData(rs),
      browserLocation = None,
      pageId = Option(rs.getString("page_id")),
      pageRole = getOptionalInt(rs, "page_role").flatMap(PageRole.fromInt),
      uniquePostId = getOptionalInt(rs, "post_id"),
      postNr = getOptionalInt(rs, "post_nr"),
      uploadHashPathSuffix = Option(rs.getString("upload_hash_path")),
      uploadFileName = Option(rs.getString("upload_file_name")),
      sizeBytes = getOptionalInt(rs, "size_bytes"),
      targetUniquePostId = getOptionalInt(rs, "target_post_id"),
      targetPageId = Option(rs.getString("target_page_id")),
      targetPostNr = getOptionalInt(rs, "target_post_nr"),
      targetUserId = getOptionalInt(rs, "target_user_id"),
      targetSiteId = Option(rs.getString("target_site_id")),
      isLoading = true)


  private def getBrowserIdData(rs: js.ResultSet) =
    BrowserIdData(
      ip = rs.getString("ip"),
      idCookie = rs.getString("browser_id_cookie"),
      fingerprint = rs.getInt("browser_fingerprint"))

}
