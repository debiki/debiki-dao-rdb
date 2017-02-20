/**
 * Copyright (C) 2017 Kaj Magnus Lindberg
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
import scala.collection.immutable
import java.{ sql => js }
import Rdb._



trait PermsOnPagesRdbMixin extends SiteTransaction {
  self: RdbSiteTransaction =>


  def insertPermsOnPages(permsOnPages: PermsOnPages): PermsOnPages = {
    val statement = s"""
      insert into perms_on_pages3 (
        site_id,
        perm_id,
        for_people_id,
        on_whole_site,
        on_category_id,
        on_page_id,
        on_post_id,
        on_tag_id,
        to_edit_page,
        to_edit_comment,
        to_edit_wiki,
        to_delete_page,
        to_delete_comment,
        to_create_page,
        to_post_comment,
        to_see)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """

    val id =
      if (permsOnPages.id != NoPermissionId)
        permsOnPages.id
      else
        selectNextPerSiteId()

    val poc = permsOnPages.copy(id = id)

    val values = List(
      siteId.asAnyRef, poc.id.asAnyRef, poc.forPeopleId.asAnyRef,
      poc.onWholeSite.orNullBoolean, poc.onCategoryId.orNullInt, poc.onPageId.orNullVarchar,
      poc.onPostId.orNullInt, poc.onTagId.orNullInt,
      poc.toEditPage.orNullBoolean, poc.toEditComment.orNullBoolean, poc.toEditWiki.orNullBoolean,
      poc.toDeletePage.orNullBoolean, poc.toDeleteComment.orNullBoolean,
      poc.toCreatePage.orNullBoolean, poc.toPostComment.orNullBoolean,
      poc.toSee.orNullBoolean)
    runUpdateExactlyOneRow(statement, values)

    poc
  }


  private def selectNextPerSiteId(): PermissionId = {
    // Let's start on 11 so 1-10 will be available in case magic ids needed.
    val query = """
      select coalesce(max(perm_id), 10) + 1 next_id from perms_on_pages3 where site_id = ?
      """
    runQueryFindExactlyOne(query, List(siteId.asAnyRef), _.getInt("next_id"))
  }


  def updatePermsOnPages(permsOnPages: PermsOnPages) {
    val statement = s"""
      update perms_on_pages3 set
        to_edit_page = ?,
        to_edit_comment = ?,
        to_edit_wiki = ?,
        to_delete_page = ?,
        to_delete_comment = ?,
        to_create_page = ?,
        to_post_comment = ?,
        to_see = ?
      where site_id = ? and perm_id = ?
      """
    val pop = permsOnPages
    val values = List(
      pop.toEditPage.orNullBoolean, pop.toEditComment.orNullBoolean, pop.toEditWiki.orNullBoolean,
      pop.toDeletePage.orNullBoolean, pop.toDeleteComment.orNullBoolean,
      pop.toCreatePage.orNullBoolean, pop.toPostComment.orNullBoolean,
      pop.toSee.orNullBoolean,
      siteId.asAnyRef, pop.id.asAnyRef)
    runUpdateExactlyOneRow(statement, values)
  }


  def loadPermsOnPages(): immutable.Seq[PermsOnPages] = {
    val query = """
      select * from perms_on_pages3 where site_id = ?
      """
    runQueryFindMany(query, List(siteId.asAnyRef), readPermsOnPages)
  }


  private def readPermsOnPages(rs: js.ResultSet): PermsOnPages = {
    PermsOnPages(
      id = rs.getInt("perm_id"),
      forPeopleId = rs.getInt("for_people_id"),
      onWholeSite = getOptBool(rs, "on_whole_site"),
      onCategoryId = getOptInt(rs, "on_category_id"),
      onPageId = getOptString(rs, "on_page_id"),
      onPostId = getOptInt(rs, "on_post_id"),
      onTagId = getOptInt(rs, "on_tag_id"),
      toEditPage = getOptBool(rs, "to_edit_page"),
      toEditComment = getOptBool(rs, "to_edit_comment"),
      toEditWiki = getOptBool(rs, "to_edit_wiki"),
      toDeletePage = getOptBool(rs, "to_delete_page"),
      toDeleteComment = getOptBool(rs, "to_delete_comment"),
      toCreatePage = getOptBool(rs, "to_create_page"),
      toPostComment = getOptBool(rs, "to_post_comment"),
      toSee = getOptBool(rs, "to_see"))
  }

}
