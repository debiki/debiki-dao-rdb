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

import collection.mutable
import collection.mutable.ArrayBuffer
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


  def nextAuditLogEntryId: AuditLogEntryId = {
    val query = "select max(audit_id) max_id from dw2_audit_log where site_id = ?"
    runQuery(query, List(siteId), rs => {
      rs.next()
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  def insertAuditLogEntry(entry: AuditLogEntry) {
    require(entry.siteId == siteId, "DwE1FWU6")
    val statement = s"""
      insert into dw2_audit_log(
        site_id,
        audit_id,
        doer_id,
        done_at,
        did_what,
        details,
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
        target_page_id,
        target_post_id,
        target_post_nr,
        target_user_id)
      values (
        ?, ?, ?, ? at time zone 'UTC',
        ?, ?, ?::inet,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """

    val values = List[AnyRef](
      entry.siteId,
      entry.id.asAnyRef,
      entry.doerId.asAnyRef,
      entry.doneAt.asTimestamp,
      entryTypeToString(entry.didWhat),
      NullVarchar,
      entry.browserIdData.ip,
      entry.browserIdData.idCookie,
      entry.browserIdData.fingerprint.asAnyRef,
      NullVarchar,
      NullVarchar,
      NullVarchar,
      NullVarchar,
      entry.pageId.orNullVarchar,
      entry.pageRole.map(_pageRoleToSql).orNullVarchar,
      entry.uniquePostId.orNullInt,
      entry.postNr.orNullInt,
      NullInt,
      NullInt,
      entry.targetPageId.orNullVarchar,
      entry.targetUniquePostId.orNullInt,
      entry.targetPostNr.orNullInt,
      entry.targetUserId.orNullInt)

    runUpdateSingleRow(statement, values)
  }


  def loadFirstAuditLogEntry(postId: UniquePostId): Option[AuditLogEntry] = {
    val query = s"""
      select
        audit_id,
        doer_id,
        done_at,
        did_what,
        details,
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
        target_page_id,
        target_post_id,
        target_post_nr,
        target_user_id
      from dw2_audit_log
      where site_id = ? and post_id = ?
      order by done_at limit 1
      """

    runQuery(query, List(siteId, postId.asAnyRef), rs => {
      if (!rs.next())
        return None

      val entry = getAuditLogEntry(rs)
      dieIf(rs.next(), "DwE4WKU7")
      Some(entry)
    })
  }


  private def getAuditLogEntry(rs: js.ResultSet) =
    AuditLogEntry(
      siteId = siteId,
      id = rs.getInt("audit_id"),
      didWhat = stringToEntryTypeTo(rs.getString("did_what")),
      doerId = rs.getInt("doer_id"),
      doneAt = getDate(rs, "done_at"),
      browserIdData = getBrowserIdData(rs),
      browserLocation = None,
      pageId = Option(rs.getString("page_id")),
      pageRole = Option(rs.getString("page_role")).map(_toPageRole),
      uniquePostId = getOptionalIntNoneNot0(rs, "post_id"),
      postNr = getOptionalIntNoneNot0(rs, "post_nr"),
      targetUniquePostId = getOptionalIntNoneNot0(rs, "target_post_id"),
      targetPageId = Option(rs.getString("target_page_id")),
      targetPostNr = getOptionalIntNoneNot0(rs, "target_post_nr"),
      targetUserId = getOptionalIntNoneNot0(rs, "target_user_id"))


  private def getBrowserIdData(rs: js.ResultSet) =
    BrowserIdData(
      ip = rs.getString("ip"),
      idCookie = rs.getString("browser_id_cookie"),
      fingerprint = rs.getInt("browser_fingerprint"))


  private def entryTypeToString(entryType: AuditLogEntryType): String = entryType match {
    case AuditLogEntryType.NewPage => "NwPg"
    case AuditLogEntryType.NewPost => "NwPs"
    case AuditLogEntryType.EditPost => "EdPs"
  }

  private def stringToEntryTypeTo(entryType: String): AuditLogEntryType = entryType match {
    case  "NwPg" => AuditLogEntryType.NewPage
    case  "NwPs" => AuditLogEntryType.NewPost
    case  "EdPs" => AuditLogEntryType.EditPost
  }

}