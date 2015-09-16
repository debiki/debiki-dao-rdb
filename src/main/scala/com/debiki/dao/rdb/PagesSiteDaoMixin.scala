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
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._
import PostsSiteDaoMixin._


/** Loads and saves pages and cached page content html.
  */
trait PagesSiteDaoMixin extends SiteDbDao with SiteTransaction {
  self: RdbSiteDao =>


  def markSectionPageContentHtmlAsStale(categoryId: CategoryId): Unit = {
    val statement = s"""
      update dw2_page_html h
        set page_version = -1, updated_at = now_utc()
        where site_id = ?
          and page_id = (
            select page_id from dw2_categories
            where site_id = ? and id = ?)
      """
    runUpdateSingleRow(statement, List(siteId, siteId, categoryId.asAnyRef))
  }


  override def loadCachedPageContentHtml(pageId: PageId): Option[(String, PageVersion)] = {
    val query = s"""
      select page_version, cached_content_html
      from dw2_page_html
      where site_id = ? and page_id = ?
      """
    runQuery(query, List(siteId, pageId.asAnyRef), rs => {
      if (!rs.next())
        return None

      val html = rs.getString("cached_content_html")
      val version = rs.getInt("page_version")
      Some(html, version)
    })
  }


  override def saveCachedPageContentHtmlPerhapsBreakTransaction(
        pageId: PageId, version: PageVersion, html: String): Boolean = {
    // 1) BUG Race condition: If the page was just deleted, something else might just
    // have removed the cached version. And here we might reinsert an old version again.
    // Rather harmless though — other parts of the system will ignore the page if it
    // doesn't exist. But this might waste a tiny bit disk space.
    // 2) Do an upsert. In 9.4 there's built in support for this, but for now:
    val updateStatement = s"""
      update dw2_page_html
        set page_version = ?, updated_at = now_utc(), cached_content_html = ?
        where site_id = ?
          and page_id = ?
          -- Don't overwrite a newer version with an older version. But do overwrite
          -- if the version is the same, because we might be rerendering because
          -- the html generation code has been changed.
          and page_version <= ?
      """
    val rowFound = runUpdateSingleRow(updateStatement, List(
      version.asAnyRef, html, siteId, pageId, version.asAnyRef))

    if (!rowFound) {
      val insertStatement = s"""
        insert into dw2_page_html (site_id, page_id, page_version, updated_at, cached_content_html)
        values (?, ?, ?, now_utc(), ?)
        """
      try {
        runUpdateSingleRow(insertStatement, List(siteId, pageId, version.asAnyRef, html))
      }
      catch {
        case exception: js.SQLException if isUniqueConstrViolation(exception) =>
          // Ok, something else generated and cached the page at the same time.
          // However now the transaction is broken.
          return false
      }
    }
    true
  }

}
