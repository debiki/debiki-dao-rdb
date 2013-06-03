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

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.DbDao._
import com.debiki.v0.EmailNotfPrefs.EmailNotfPrefs
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import scala.collection.{mutable => mut}
import RelDb.pimpOptionWithNullVarchar
import RelDbUtil._
import collection.mutable.StringBuilder


trait CreateSiteSystemDaoMixin {
  self: RelDbSystemDbDao =>


  def createSiteImpl(siteData: FirstSiteData): Tenant = {
    val newSiteOwnerData = siteData match {
      case n: NewSiteData => Some(n.newSiteOwnerData)
      case _ => None
    }

    val anyOwnerIp = newSiteOwnerData.map(_.ownerIp)

    db.transaction { implicit connection =>
      val siteId: String = db.nextSeqNo("DW1_TENANTS_ID").toString
      db.update("""
        insert into DW1_TENANTS (ID, NAME)
        values (?, ?)
        """, List[AnyRef](siteId, siteData.name))
      val siteHost = TenantHost(siteData.address, TenantHost.RoleCanonical, siteData.https)
      insertTenantHost(siteId, siteHost)(connection)

      val newWebsiteDao = new RelDbTenantDbDao(
        QuotaConsumers(tenantId = siteId, ip = anyOwnerIp, roleId = None), self)

      // Could send an email, see AppCreateWebsite.createWebsite() in project debiki-app-play.

      for (pageNoSiteId <- siteData.pagesToCreate) {
        val page = pageNoSiteId.copyWithNewSiteId(siteId)
        newWebsiteDao.createPageImpl(page)(connection)
      }

      Tenant(siteId, name = siteData.name, creatorIp = "",
         creatorTenantId = "", creatorLoginId = "",
         creatorRoleId = "", hosts = siteHost::Nil)
    }
  }


  def insertTenantHost(tenantId: String, host: TenantHost)(connection:  js.Connection) = {
    val cncl = host.role match {
      case TenantHost.RoleCanonical => "C"
      case TenantHost.RoleRedirect => "R"
      case TenantHost.RoleLink => "L"
      case TenantHost.RoleDuplicate => "D"
    }
    val https = host.https match {
      case TenantHost.HttpsRequired => "R"
      case TenantHost.HttpsAllowed => "A"
      case TenantHost.HttpsNone => "N"
    }
    val sql = """
      insert into DW1_TENANT_HOSTS (TENANT, HOST, CANONICAL, HTTPS)
      values (?, ?, ?, ?)
      """
    db.update(sql, List(tenantId, host.address, cncl, https))(connection)
  }

}


