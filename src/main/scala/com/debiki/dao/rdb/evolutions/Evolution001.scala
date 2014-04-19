/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
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

package com.debiki.dao.rdb.evolutions

import com.debiki.core._
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import com.debiki.dao.rdb._
import _root_.java.{util => ju, io => jio}
import java.{sql => js}
import collection.mutable.StringBuilder
import Rdb.pimpOptionWithNullVarchar


/** Updates new fields DW1_PAGES.CACHED_NUM_LIKES and _WRONGS, and some other fields
  * that might not yet have been initialized, e.g. CACHED_TITLE.
  */
class Evolution001(private val dao: RdbSystemDao) {

  def db = dao.db

  def apply() {
    val allSitePageIds = db.queryAtnms(
      "select TENANT SITE_ID, GUID ID from DW1_PAGES where CACHED_NUM_LIKES = -1", Nil, rs => {
      var sitePageIds = Vector[SitePageId]()
      while (rs.next()) {
        val siteId = rs.getString("SITE_ID")
        val pageId = rs.getString("ID")
        sitePageIds :+= SitePageId(siteId, pageId)
      }
      sitePageIds
    })

    for (sitePageId <- allSitePageIds) {
      loadPageAndUpdateTable(sitePageId)
    }
  }


  def loadPageAndUpdateTable(sitePageId: SitePageId) {
    val siteDao = dao.newSiteDao(sitePageId.siteId)
    val pageParts = siteDao.loadPageParts(sitePageId.pageId) getOrDie("DwE75FX0")
    val oldMeta = siteDao.loadPageMeta(sitePageId.pageId) getOrDie("DwE3GF88")
    val newMeta = PageMeta.forChangedPage(oldMeta, changedPage = pageParts)
    siteDao.updatePageMeta(newMeta, old = oldMeta)
  }

}

