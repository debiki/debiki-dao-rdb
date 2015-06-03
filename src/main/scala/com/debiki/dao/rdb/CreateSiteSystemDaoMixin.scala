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


trait CreateSiteSystemDaoMixin {
  self: RdbSystemDao =>


  def insertTenantHost(tenantId: String, host: SiteHost)(connection:  js.Connection) = {
    val cncl = host.role match {
      case SiteHost.RoleCanonical => "C"
      case SiteHost.RoleRedirect => "R"
      case SiteHost.RoleLink => "L"
      case SiteHost.RoleDuplicate => "D"
    }
    val sql = """
      insert into DW1_TENANT_HOSTS (SITE_ID, HOST, CANONICAL)
      values (?, ?, ?)
      """
    db.update(sql, List(tenantId, host.hostname, cncl))(connection)
  }

}


