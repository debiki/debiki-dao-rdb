/**
 * Copyright (C) 2013 Kaj Magnus Lindberg (born 1979)
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
import com.debiki.core.EmailNotfPrefs.EmailNotfPrefs
import com.debiki.core.PagePath._
import com.debiki.core.Prelude._
import _root_.java.{util => ju, io => jio, sql => js}
import RdbUtil._
import collection.mutable.StringBuilder


trait CreateSiteSystemDaoMixin extends SystemTransaction {
  self: RdbSystemDao =>


  def deleteAnyHostname(hostname: String): Boolean = {
    // For now, safety check. Remove if needed.
    require(hostname.startsWith(SiteHost.E2eTestPrefix), "EdE5GPQ0V")
    val sql = """
      delete from hosts3 where host = ?
      """
    runUpdateSingleRow(sql, List(hostname))
  }


  def insertSiteHost(tenantId: String, host: SiteHost) {
    val cncl = host.role match {
      case SiteHost.RoleCanonical => "C"
      case SiteHost.RoleRedirect => "R"
      case SiteHost.RoleLink => "L"
      case SiteHost.RoleDuplicate => "D"
    }
    val sql = """
      insert into hosts3 (SITE_ID, HOST, CANONICAL)
      values (?, ?, ?)
      """
    val inserted =
      try runUpdateSingleRow(sql, List(tenantId, host.hostname, cncl))
      catch {
        case ex: js.SQLException =>
          if (Rdb.isUniqueConstrViolation(ex) &&
              Rdb.uniqueConstrViolatedIs("dw1_tnthsts_host__u", ex))
            throw DuplicateHostnameException(host.hostname)
          else
            throw ex
      }
    dieIf(!inserted, "EdE4KEWW2")
  }


  def deleteSiteByName(name: String): Boolean = {
    require(name startsWith SiteHost.E2eTestPrefix, "Can delete test sites only [EdE4PF0Y4]")
    val site = loadSites().find(_.name == name) getOrElse {
      return false
    }
    deleteSiteById(site.id)
  }


  def deleteSiteById(siteId: SiteId): Boolean = {
    require(siteId startsWith Site.TestIdPrefix, "Can delete test sites only [EdE6FK02]")
    require(siteId != FirstSiteId, "Cannot delete site 1 [EdE5RCTW3]")

    runUpdate("set constraints all deferred")

    // Dupl code [7KUW0ZT2]
    val statements = (s"""
      delete from index_queue3 where site_id = ?
      delete from spam_check_queue3 where site_id = ?
      delete from audit_log3 where site_id = ?
      delete from review_tasks3 where site_id = ?
      delete from settings3 where site_id = ?
      delete from member_page_settings3 where site_id = ?
      delete from post_read_stats3 where site_id = ?
      delete from notifications3 where site_id = ?
      delete from emails_out3 where site_id = ?
      delete from upload_refs3 where site_id = ?""" +
      // skip: uploads3
      s"""
      delete from page_members3 where site_id = ?
      delete from page_users3 where site_id = ?
      delete from tag_notf_levels3 where site_id = ?
      delete from post_tags3 where site_id = ?
      delete from post_actions3 where site_id = ?
      delete from post_revisions3 where site_id = ?
      delete from posts3 where site_id = ?
      delete from page_paths3 where site_id = ?
      delete from page_html3 where site_id = ?
      delete from pages3 where site_id = ?
      delete from categories3 where site_id = ?
      delete from blocks3 where site_id = ?
      delete from guest_prefs3 where site_id = ?
      delete from identities3 where site_id = ?
      delete from invites3 where site_id = ?
      delete from user_visit_stats3 where site_id = ?
      delete from user_stats3 where site_id = ?
      delete from usernames3 where site_id = ?
      delete from users3 where site_id = ?
      delete from hosts3 where site_id = ?
      """).trim.split("\n")

    statements foreach { statement =>
      runUpdate(statement, List(siteId))
    }

    val isSiteGone = runUpdateSingleRow("delete from sites3 where id = ?", List(siteId))

    runUpdate("set constraints all immediate")
    isSiteGone
  }

}


