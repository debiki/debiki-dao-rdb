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
import java.{sql => js, util => ju}
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import Rdb._
import RdbUtil._


/** Loads and saves categories.
  */
trait CategoriesSiteDaoMixin extends SiteDbDao with SiteTransaction {
  self: RdbSiteDao =>


  def loadCategory(categoryId: CategoryId): Option[Category] = {
    loadCategoryMap().get(categoryId)
  }


  def loadCategoryMap(): Map[CategoryId, Category] = {
    val query = """
      select * from dw2_categories where site_id = ?
      """
    var result = Map[CategoryId, Category]()
    runQueryPerhapsAtnms(query, List(siteId), rs => {
      while (rs.next()) {
        val category = getCategory(rs)
        result += category.id -> category
      }
    })
    result
  }


  def loadPagesInCategories(categoryIds: Seq[CategoryId], pageQuery: PageQuery, limit: Int)
        : Seq[PagePathAndMeta] = {

    require(limit >= 1, "DwE5KGW2")
    var values = Vector[AnyRef]()

    val (orderBy, offsetTestAnd) = pageQuery.orderOffset match {
      case PageOrderOffset.Any =>
        ("", "")
      case PageOrderOffset.ByPublTime =>
        ("order by g.published_at desc", "")
      case PageOrderOffset.ByBumpTime(anyDate) =>
        val offsetTestAnd = anyDate match {
          case None => ""
          case Some(date) =>
            values :+= d2ts(date)
            "g.bumped_at <= ? and"
        }
        // bumped_at is never null (it defaults to publ date or creation date).
        (s"order by g.bumped_at desc", offsetTestAnd)
      case PageOrderOffset.ByPinOrderLoadOnlyPinned =>
        (s"order by g.pin_order", "g.pin_where is not null and")
      case PageOrderOffset.ByLikesAndBumpTime(anyLikesAndDate) =>
        val offsetTestAnd = anyLikesAndDate match {
          case None => ""
          case Some((maxNumLikes, date)) =>
            values :+= maxNumLikes.asAnyRef
            values :+= d2ts(date)
            values :+= maxNumLikes.asAnyRef
            """((g.num_likes <= ? and g.bumped_at <= ?) or
                (g.num_likes < ?)) and"""
        }
        ("order by g.num_likes desc, bumped_at desc", offsetTestAnd)
      case _ =>
        unimplemented(s"Sort order unsupported: ${pageQuery.orderOffset} [DwE2GFU06]")
    }

    values :+= siteId

    val pageFilterAnd = pageQuery.pageFilter match {
      case PageFilter.ShowOpenQuestionsTodos =>
        import PageRole._
        o"""
          g.page_role in (${Question.toInt}, ${Problem.toInt}, ${Idea.toInt}, ${ToDo.toInt}) and
          g.closed_at is null and
          """
      case PageFilter.ShowAll =>
        ""
    }

    values = values ++ categoryIds.map(_.asAnyRef)

    val sql = s"""
        select
          t.parent_folder,
          t.page_id,
          t.show_id,
          t.page_slug,
          ${_PageMetaSelectListItems}
        from dw1_pages g left join dw1_page_paths t
          on g.site_id = t.site_id and g.page_id = t.page_id
          and t.canonical = 'C'
        where
          $offsetTestAnd
          $pageFilterAnd
          g.site_id = ? and
          g.page_role <> ${PageRole.Forum.toInt} and
          g.category_id in (${ makeInListFor(categoryIds) })
        $orderBy
        limit $limit"""

    var result = ArrayBuffer[PagePathAndMeta]()

    db.withConnection { implicit connection =>
      db.query(sql, values.toList, rs => {
        while (rs.next) {
          val pagePath = _PagePath(rs, siteId)
          val pageMeta = _PageMeta(rs, pagePath.pageId.get)
          result.append(PagePathAndMeta(pagePath, pageMeta))
        }
      })
    }
    result.toSeq
  }


  override def nextCategoryId(): UniquePostId = {
    val query = """
      select max(id) max_id from dw2_categories where site_id = ?
      """
    runQuery(query, List(siteId), rs => {
      rs.next()
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  override def insertCategory(category: Category) {
    val statement = """
      insert into dw2_categories (
        site_id, id, page_id, parent_id,
        name, slug, position,
        description, new_topic_types,
        created_at, updated_at)
      values (
        ?, ?, ?, ?,
        ?, ?, ?,
        ?, ?,
        ?, ?)"""
    val values = List[AnyRef](
      siteId, category.id.asAnyRef, category.sectionPageId, category.parentId.orNullInt,
      category.name, category.slug, category.position.asAnyRef,
      category.description.orNullVarchar, category.newTopicTypes.map(_.toInt).mkString(","),
      currentTime, currentTime)
    runUpdateSingleRow(statement, values)
  }


  override def updateCategory(category: Category) {
    val statement = """
      update dw2_categories set
        page_id = ?, parent_id = ?,
        name = ?, slug = ?, position = ?,
        description = ?, new_topic_types = ?,
        created_at = ?, updated_at = ?
      where site_id = ? and id = ?"""
    val values = List[AnyRef](
      category.sectionPageId, category.parentId.orNullInt,
      category.name, category.slug, category.position.asAnyRef,
      category.description.orNullVarchar, category.newTopicTypes.map(_.toInt).mkString(","),
      category.createdAt.asTimestamp, category.updatedAt.asTimestamp,
      siteId, category.id.asAnyRef)
    runUpdateSingleRow(statement, values)
  }


  private def getCategory(rs: js.ResultSet): Category = {
    Category(
      id = rs.getInt("id"),
      sectionPageId = rs.getString("page_id"),
      parentId = getOptionalIntNoneNot0(rs, "parent_id"),
      position = rs.getInt("position"),
      name = rs.getString("name"),
      slug = rs.getString("slug"),
      description = Option(rs.getString("description")),
      newTopicTypes = getNewTopicTypes(rs),
      createdAt = getDate(rs, "created_at"),
      updatedAt = getDate(rs, "updated_at"),
      lockedAt = getOptionalDate(rs, "locked_at"),
      frozenAt = getOptionalDate(rs, "frozen_at"),
      deletedAt = getOptionalDate(rs, "deleted_at"))
  }


  private def getNewTopicTypes(rs: js.ResultSet): immutable.Seq[PageRole] = {
    // This is a comma separated topic type list, like: "5,3,11".
    val newTopicTypes: immutable.Seq[PageRole] = Option(rs.getString("new_topic_types")) match {
      case Some(text) if text.nonEmpty =>
        val topicTypeIdStrings = text.split(',')
        var typeIds = Vector[PageRole]()
        for (typeIdString <- topicTypeIdStrings) {
          // COULD log an error instead of silently ignoring errors here?
          Try(typeIdString.toInt) foreach { typeIdInt =>
            PageRole.fromInt(typeIdInt) foreach { pageRole: PageRole =>
              typeIds :+= pageRole
            }
          }
        }
        typeIds
      case _ =>
        Nil
    }
    newTopicTypes
  }

}


