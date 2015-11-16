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
import java.{sql => js}
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import Rdb._
import RdbUtil._


/** Loads and saves categories, and lists all pages in a category or all categories.
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
      case PageFilter.ShowWaiting =>
        import PageRole._
        s"""
          g.page_role in (
            ${Question.toInt}, ${Problem.toInt}, ${Idea.toInt}, ${ToDo.toInt},
            ${Critique.toInt}) and  -- [plugin]
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

    val result = ArrayBuffer[PagePathAndMeta]()

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


  override def insertCategoryMarkSectionPageStale(category: Category) {
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
      category.description.orNullVarchar, topicTypesToVarchar(category.newTopicTypes),
      currentTime, currentTime)
    runUpdateSingleRow(statement, values)
    markSectionPageContentHtmlAsStale(category.id)
  }


  override def updateCategoryMarkSectionPageStale(category: Category) {
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
      category.description.orNullVarchar, topicTypesToVarchar(category.newTopicTypes),
      category.createdAt.asTimestamp, category.updatedAt.asTimestamp,
      siteId, category.id.asAnyRef)
    runUpdateSingleRow(statement, values)
    // In the future: mark any old section page html as stale too, if moving to new section.
    markSectionPageContentHtmlAsStale(category.id)
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


  private def topicTypesToVarchar(topicTypes: Seq[PageRole]): AnyRef =
    topicTypes.map(_.toInt).mkString(",") orIfEmpty NullVarchar


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


/*
Old code that recursively finds all ancestor pages of a page. Was in use
before I created the categories table. Perhaps it'll be useful again in the future
if there'll be really many categories sometimes, so one doesn't want to load all of them?
def batchLoadAncestorIdsParentFirst(pageIds: List[PageId])(connection: js.Connection)
    : collection.Map[PageId, List[PageId]] = {
  // This complicated stuff will go away when I create a dedicated category table,
  // and add forum_id, category_id, sub_cat_id columns to the pages table and the
  // category page too? Then everything will be available instantly.
  // (O.t.o.h. one will need to keep the above denormalized fields up-to-date.)

  val pageIdList = makeInListFor(pageIds)

  val sql = s"""
    with recursive ancestor_page_ids(child_id, parent_id, site_id, path, cycle) as (
        select
          page_id::varchar child_id,
          parent_page_id::varchar parent_id,
          site_id,
          -- `|| ''` needed otherwise conversion to varchar[] doesn't work, weird
          array[page_id || '']::varchar[],
          false
        from dw1_pages where site_id = ? and page_id in ($pageIdList)
      union all
        select
          page_id::varchar child_id,
          parent_page_id::varchar parent_id,
          dw1_pages.site_id,
          path || page_id,
          parent_page_id = any(path) -- aborts if cycle, don't know if works (never tested)
        from dw1_pages join ancestor_page_ids
        on dw1_pages.page_id = ancestor_page_ids.parent_id and
           dw1_pages.site_id = ancestor_page_ids.site_id
        where not cycle
    )
    select path from ancestor_page_ids
    order by array_length(path, 1) desc
    """

  // If asking for ids for many pages, e.g. 2 pages, the result migth look like this:
  //  path
  //  -------------------
  //  {61bg6,1f4q9,51484}
  //  {1f4q9,51484}
  //  {61bg6,1f4q9}
  //  {1f4q9}
  //  {61bg6}
  // if asking for ancestors of page 1f4q9 and 61bg6.
  // I don't know if it's possible to group by the first element in an array,
  // and keep only the longest array in each group? Instead, for now,
  // for each page, simply use the longest path found.

  val result = mut.Map[PageId, List[PageId]]()
  db.withConnection { implicit connection =>
    db.query(sql, siteId :: pageIds, rs => {
      while (rs.next()) {
        val sqlArray: java.sql.Array = rs.getArray("path")
        val pageIdPathSelfFirst = sqlArray.getArray.asInstanceOf[Array[PageId]].toList
        val pageId::ancestorIds = pageIdPathSelfFirst
        // Update `result` if we found longest list of ancestors thus far, for pageId.
        val lengthOfStoredPath = result.get(pageId).map(_.length) getOrElse -1
        if (lengthOfStoredPath < ancestorIds.length) {
          result(pageId) = ancestorIds
        }
      }
    })
  }
  result
}
  */


