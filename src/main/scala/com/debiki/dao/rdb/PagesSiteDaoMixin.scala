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
trait PagesSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>


  def loadOpenChatsPinnedGlobally(): immutable.Seq[PageMeta] = {
    val sql = s"""
      select g.page_id, ${_PageMetaSelectListItems}
      from pages3 g
      where g.site_id = ?
        and g.page_role = ${PageRole.OpenChat.toInt}
        and g.pin_order is not null
        and g.pin_where = ${PinPageWhere.Globally.toInt}
      order by g.pin_order desc
      """
    runQueryFindMany(sql, List(siteId.asAnyRef), rs => {
      _PageMeta(rs)
    })
  }


  def markPagesWithUserAvatarAsStale(userId: UserId) {
    // Currently a user's avatar is shown in posts written by him/her.
    val statement = s"""
      update pages3
        set version = version + 1, updated_at = now_utc()
        where site_id = ?
          and page_id in (
            select distinct page_id from posts3
            where site_id = ? and created_by_id = ?)
      """
    runUpdate(statement, List(siteId.asAnyRef, siteId.asAnyRef, userId.asAnyRef))
  }


  def markSectionPageContentHtmlAsStale(categoryId: CategoryId) {
    val statement = s"""
      update page_html3 h
        set page_version = -1, updated_at = now_utc()
        where site_id = ?
          and page_id = (
            select page_id from categories3
            where site_id = ? and id = ?)
      """
    runUpdateSingleRow(statement, List(siteId.asAnyRef, siteId.asAnyRef, categoryId.asAnyRef))
  }


  override def loadCachedPageContentHtml(pageId: PageId): Option[(String, CachedPageVersion)] = {
    val query = s"""
      select site_version, page_version, app_version, data_hash, html
      from page_html3
      where site_id = ? and page_id = ?
      """
    runQuery(query, List(siteId.asAnyRef, pageId.asAnyRef), rs => {
      if (!rs.next())
        return None

      val html = rs.getString("html")
      val version = getCachedPageVersion(rs)
      dieIf(rs.next(), "DwE5KGF2")

      Some(html, version)
    })
  }


  override def saveCachedPageContentHtmlPerhapsBreakTransaction(
        pageId: PageId, version: CachedPageVersion, html: String): Boolean = {
    // 1) BUG Race condition: If the page was just deleted, something else might just
    // have removed the cached version. And here we might reinsert an old version again.
    // Rather harmless though — other parts of the system will ignore the page if it
    // doesn't exist. But this might waste a tiny bit disk space.
    // 2) Do an upsert. In 9.4 there's built in support for this, but for now:
    val updateStatement = s"""
      update page_html3
        set site_version = ?,
            page_version = ?,
            app_version = ?,
            data_hash = ?,
            updated_at = now_utc(),
            html = ?
        where site_id = ?
          and page_id = ?
          -- Don't overwrite a newer version with an older version. But do overwrite
          -- if the version is the same, because we might be rerendering because
          -- the app version (i.e. the html generation code) has been changed.
          and site_version <= ?
          and page_version <= ?
      """
    val rowFound = runUpdateSingleRow(updateStatement, List(
      version.siteVersion.asAnyRef, version.pageVersion.asAnyRef,
      version.appVersion, version.dataHash, html, siteId.asAnyRef, pageId,
      version.siteVersion.asAnyRef, version.pageVersion.asAnyRef))

    if (!rowFound) {
      val insertStatement = s"""
        insert into page_html3 (
          site_id, page_id,
          site_version, page_version, app_version,
          data_hash, updated_at, html)
        values (?, ?, ?, ?, ?, ?, now_utc(), ?)
        """
      try {
        runUpdateSingleRow(insertStatement, List(
          siteId.asAnyRef, pageId,
          version.siteVersion.asAnyRef, version.pageVersion.asAnyRef, version.appVersion,
          version.dataHash, html))
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


  def loadPagePopularityScore(pageId: PageId): Option[PagePopularityScores] = {
    val sql = s"""
      select * from page_popularity_scores3
      where site_id = ?
        and page_id = ?
      """
    runQueryFindOneOrNone(sql, List(siteId.asAnyRef, pageId), rs => {
      PagePopularityScores(
        pageId = rs.getString("page_id"),
        updatedAt = getWhen(rs, "updated_at"),
        algorithmVersion = rs.getInt("algorithm"),
        dayScore = rs.getFloat("day_score"),
        weekScore = rs.getFloat("week_score"),
        monthScore = rs.getFloat("month_score"),
        quarterScore = rs.getFloat("quarter_score"),
        yearScore = rs.getFloat("year_score"),
        allScore = rs.getFloat("all_score"))
    })
  }


  def upsertPagePopularityScore(scores: PagePopularityScores) {
    val statement = s"""
      insert into page_popularity_scores3 (
        site_id,
        page_id,
        popular_since,
        updated_at,
        algorithm,
        day_score,
        week_score,
        month_score,
        quarter_score,
        year_score,
        all_score)
      values (?, ?, now_utc(), now_utc(), ?, ?, ?, ?, ?, ?, ?)
      on conflict (site_id, page_id) do update set
        updated_at = excluded.updated_at,
        algorithm = excluded.algorithm,
        day_score = excluded.day_score,
        week_score = excluded.week_score,
        month_score = excluded.month_score,
        quarter_score = excluded.quarter_score,
        year_score = excluded.year_score,
        all_score = excluded.all_score
      """

    val values = List(
      siteId.asAnyRef, scores.pageId, scores.algorithmVersion.asAnyRef,
      scores.dayScore.asAnyRef, scores.weekScore.asAnyRef,
      scores.monthScore.asAnyRef, scores.quarterScore.asAnyRef,
      scores.yearScore.asAnyRef, scores.allScore.asAnyRef)

    runUpdateSingleRow(statement, values)
  }


  def insertAltPageId(altPageId: AltPageId, realPageId: PageId) {
    val statement = s"""
      insert into alt_page_ids3 (site_id, alt_page_id, real_page_id)
      values (?, ?, ?)
      """
    runUpdateExactlyOneRow(statement, List(siteId.asAnyRef, altPageId, realPageId.asAnyRef))
  }


  def listAltPageIds(realPageId: PageId): Set[AltPageId] = {
    val sql = s"""
      select alt_page_id
      from alt_page_ids3
      where site_id = ?
        and real_page_id = ?
      """
    runQueryFindManyAsSet(sql, List(siteId.asAnyRef, realPageId.asAnyRef), rs => {
      rs.getString("alt_page_id")
    })
  }


  def loadRealPageId(altPageId: AltPageId): Option[PageId] = {
    val sql = s"""
      select real_page_id
      from alt_page_ids3
      where site_id = ?
        and alt_page_id = ?
      """
    runQueryFindOneOrNone(sql, List(siteId.asAnyRef, altPageId), rs => {
      rs.getString("real_page_id")
    })
  }

}

