/**
 * Copyright (C) 2011-2013 Kaj Magnus Lindberg (born 1979)
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
import com.debiki.core.{PostActionPayload => PAP}
import com.debiki.core.Prelude._
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import java.{sql => js}
import scala.collection.{immutable, mutable}
import scala.collection.{mutable => mut}
import scala.collection.mutable.StringBuilder
import DbDao._
import Rdb._
import RdbUtil._


/** A relational database Data Access Object, for a specific website.
  *
  * Could/should split it into many smaller mixins, like
  * FullTextSearchSiteDaoMixin. But not very important, because
  * it doesn't have any mutable state.
  */
class RdbSiteDao(
  val quotaConsumers: QuotaConsumers,
  val daoFactory: RdbDaoFactory)
  extends SiteDbDao
  with FullTextSearchSiteDaoMixin
  with UserSiteDaoMixin
  with UserActionInfoSiteDaoMixin
  with LoginSiteDaoMixin
  with PostsReadStatsSiteDaoMixin
  with NotificationsSiteDaoMixin
  with SettingsSiteDaoMixin {


  val MaxWebsitesPerIp = 6

  val LocalhostAddress = "127.0.0.1"

  def siteId = quotaConsumers.tenantId

  def db = systemDaoSpi.db

  def systemDaoSpi = daoFactory.systemDbDao

  def fullTextSearchIndexer = daoFactory.fullTextSearchIndexer

  def commonMarkRenderer: CommonMarkRenderer = daoFactory.commonMarkRenderer


  /** Some SQL operations might cause harmless errors, then we try again.
    *
    * One harmless error: Generating random ids and one happens to clash with
    * an existing id. Simply try again with another id.
    * Another harmless error (except w.r.t. performance) is certain deadlocks.
    * See the implementation of savePageActions() for details, and also:
    * see: http://www.postgresql.org/message-id/1078934613.17553.66.camel@coppola.ecircle.de
    * and: < http://postgresql.1045698.n5.nabble.com/
    *         Foreign-Keys-and-Deadlocks-tp4962572p4967236.html >
    */
  private def tryManyTimes[T](numTimes: Int)(sqlBlock: => T): T = {
    for (i <- 2 to numTimes) {
      try {
        return sqlBlock
      }
      catch {
        case ex: js.SQLException =>
          // log.warning(...
          println(s"SQLException caught but I will try again: $ex")
      }
    }
    // Don't catch exceptions during very last attempt.
    sqlBlock
  }


  def nextPageId(): PageId = {
    db.transaction { connection =>
      nextPageIdImpl(connection)
    }
  }


  private def nextPageIdImpl(implicit connecton: js.Connection): PageId = {
    val sql = """{? = call INC_NEXT_PAGE_ID(?) }"""
    var nextPageIdInt =
      db.call(sql, List(siteId), js.Types.INTEGER, result => {
        val nextId = result.getInt(1)
        nextId
      })
    nextPageIdInt.toString
  }


  def createPage(pagePerhapsId: Page): Page = {
    db.transaction { implicit connection =>
      createPageImpl(pagePerhapsId)(connection)
    }
  }


  def createPageImpl(pagePerhapsId: Page)(connection: js.Connection): Page = {
    // Could wrap this whole function in `tryManyTimes { ... }` because the id
    // might clash with an already existing id? (The ids in use are fairly short,
    // currently, because they're sometimes shown in the url)
    var page = if (pagePerhapsId.hasIdAssigned) {
      // Fine, a valid new page id has been assigned somewhere else?
      pagePerhapsId
    } else {
      val nextId = nextPageIdImpl(connection)
      pagePerhapsId.copyWithNewId(nextId)  // COULD ensure same
                                          // method used in all DAO modules!
    }

    require(page.siteId == siteId)
    // SHOULD throw a recognizable exception on e.g. dupl page slug violation.
    _createPage(page)(connection)

    // Now, when saving actions, start with an empty page, or there'll be
    // id clashes when savePageActionsImpl adds the saved actions to
    // the page (since the title/body creation actions would already be present).
    val emptyPage = PageNoPath(PageParts(page.id), page.ancestorIdsParentFirst,
      page.meta.copy(pageExists = true))
    val (newPageNoPath, rawActionsWithIds) =
      savePageActionsImpl(emptyPage, page.parts.rawActions)(connection)

    Page(newPageNoPath.meta, page.path, page.ancestorIdsParentFirst, newPageNoPath.parts)
  }


  def loadPageMeta(pageId: PageId): Option[PageMeta] = loadPageMeta(pageId, None)


  def loadPageMeta(pageId: PageId, anySiteId: Option[SiteId]): Option[PageMeta] = {
    loadPageMetasAsMap(pageId::Nil, anySiteId) get pageId
  }


  def loadPageMetasAsMap(pageIds: Seq[PageId], anySiteId: Option[SiteId] = None)
        : Map[PageId, PageMeta] = {
    if (pageIds.isEmpty) return Map.empty
    db.withConnection { loadPageMetaImpl(pageIds, anySiteId)(_) }
  }


  def loadPageMetaImpl(pageIds: Seq[PageId], anySiteId: Option[SiteId] = None)(
        connection: js.Connection): Map[PageId, PageMeta] = {
    assErrIf(pageIds.isEmpty, "DwE84KF0")
    val values = anySiteId.getOrElse(siteId) :: pageIds.toList
    val sql = s"""
        select g.GUID, ${_PageMetaSelectListItems}
        from DW1_PAGES g
        where g.TENANT = ? and g.GUID in (${ makeInListFor(pageIds) })
        """
    var metaByPageId = Map[PageId, PageMeta]()
    db.query(sql, values, rs => {
      while (rs.next) {
        val pageId = rs.getString("GUID")
        val meta = _PageMeta(rs, pageId = pageId)
        metaByPageId += pageId -> meta
      }
    })(connection)
    metaByPageId
  }


  def updatePageMeta(meta: PageMeta, old: PageMeta) {
    db.transaction {
      _updatePageMeta(meta, anyOld = Some(old))(_)
    }
  }


  private def _updatePageMeta(newMeta: PageMeta, anyOld: Option[PageMeta])
        (implicit connection: js.Connection) {
    val values = List(
      newMeta.parentPageId.orNullVarchar,
      newMeta.embeddingPageUrl.orNullVarchar,
      d2ts(newMeta.modDati),
      o2ts(newMeta.pubDati),
      o2ts(newMeta.sgfntModDati),
      newMeta.cachedTitle.orNullVarchar,
      newMeta.cachedAuthorDispName orIfEmpty NullVarchar,
      newMeta.cachedAuthorUserId orIfEmpty NullVarchar,
      newMeta.cachedNumPosters.asInstanceOf[AnyRef],
      newMeta.cachedNumActions.asInstanceOf[AnyRef],
      newMeta.cachedNumLikes.asAnyRef,
      newMeta.cachedNumWrongs.asAnyRef,
      newMeta.cachedNumPostsToReview.asInstanceOf[AnyRef],
      newMeta.cachedNumPostsDeleted.asInstanceOf[AnyRef],
      newMeta.cachedNumRepliesVisible.asInstanceOf[AnyRef],
      o2ts(newMeta.cachedLastVisiblePostDati),
      newMeta.cachedNumChildPages.asInstanceOf[AnyRef],
      siteId,
      newMeta.pageId,
      _pageRoleToSql(newMeta.pageRole))
    val sql = s"""
      update DW1_PAGES set
        PARENT_PAGE_ID = ?,
        EMBEDDING_PAGE_URL = ?,
        MDATI = ?,
        PUBL_DATI = ?,
        SGFNT_MDATI = ?,
        CACHED_TITLE = ?,
        CACHED_AUTHOR_DISPLAY_NAME = ?,
        CACHED_AUTHOR_USER_ID = ?,
        CACHED_NUM_POSTERS = ?,
        CACHED_NUM_ACTIONS = ?,
        CACHED_NUM_LIKES = ?,
        CACHED_NUM_WRONGS = ?,
        CACHED_NUM_POSTS_TO_REVIEW = ?,
        CACHED_NUM_POSTS_DELETED = ?,
        CACHED_NUM_REPLIES_VISIBLE = ?,
        CACHED_LAST_VISIBLE_POST_DATI = ?,
        CACHED_NUM_CHILD_PAGES = ?
      where TENANT = ? and GUID = ? and PAGE_ROLE = ?
      """

    val numChangedRows = db.update(sql, values)

    if (numChangedRows == 0)
      throw DbDao.PageNotFoundByIdAndRoleException(
        siteId, newMeta.pageId, newMeta.pageRole)
    if (2 <= numChangedRows)
      assErr("DwE4Ikf1")

    val newParentPage = anyOld.isEmpty || newMeta.parentPageId != anyOld.get.parentPageId
    if (newParentPage) {
      anyOld.flatMap(_.parentPageId) foreach { updateParentPageChildCount(_, -1) }
      newMeta.parentPageId foreach { updateParentPageChildCount(_, +1) }
    }
  }


  override def loadAncestorIdsParentFirst(pageId: PageId): List[PageId] = {
    db.withConnection { connection =>
      loadAncestorIdsParentFirstImpl(pageId)(connection)
    }
  }


  private def loadAncestorIdsParentFirstImpl(pageId: PageId)(connection: js.Connection)
        : List[PageId] = {
    batchLoadAncestorIdsParentFirst(pageId::Nil)(connection).get(pageId) getOrElse Nil
  }


  def batchLoadAncestorIdsParentFirst(pageIds: List[PageId])(connection: js.Connection)
      : collection.Map[PageId, List[PageId]] = {
    val pageIdList = makeInListFor(pageIds)

    val sql = s"""
      with recursive ancestor_page_ids(child_id, parent_id, tenant, path, cycle) as (
          select
            guid::varchar child_id,
            parent_page_id::varchar parent_id,
            tenant,
            -- `|| ''` needed otherwise conversion to varchar[] doesn't work, weird
            array[guid || '']::varchar[],
            false
          from dw1_pages where tenant = ? and guid in ($pageIdList)
        union all
          select
            guid::varchar child_id,
            parent_page_id::varchar parent_id,
            dw1_pages.tenant,
            path || guid,
            parent_page_id = any(path) -- aborts if cycle, don't know if works (never tested)
          from dw1_pages join ancestor_page_ids
          on dw1_pages.guid = ancestor_page_ids.parent_id and
             dw1_pages.tenant = ancestor_page_ids.tenant
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
    db.transaction { implicit connection =>
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


  def loadCategoryTree(rootPageId: PageId): Seq[Category] = {

    // The below SQL selects rows like so:
    //
    // category_id | sub_category_id |                  category_name
    // -------------+-----------------+--------------------------------------------------
    // 110j7       |                 | Sandbox (test forum)
    // 110j7       | 71cs1           | Some Sandbox Sub Category
    // 110j7       | 62nk9           | Another Sandbox Sub Category
    // 1d8z5       |                 | General
    // 1d8z5       | 84472           | Sub Category of General
    // 1d8z5       | 71py0           | Yet Another Sub Category
    //
    // That is, a category directly followed by all its sub categories. And only
    // two levels of categories is allowed.

    val sql = i"""
      with categories as (
        select guid category_id, null::varchar sub_category_id, cached_title category_name
        from dw1_pages
        where
          parent_page_id = ? and
          page_role = 'FC' and
          tenant = ?),
      sub_categories as (
        select parent_page_id category_id, guid sub_categories, cached_title category_name
        from dw1_pages
        where
          parent_page_id in (select category_id from categories) and
          page_role = 'FC' and
          tenant = ?)
      select * from categories
      union
      select * from sub_categories
      order by category_id, sub_category_id desc;
      """

    var allCategories = Vector[Category]()
    var anyCurrentCategory: Option[Category] = None

    db.queryAtnms(sql, List[AnyRef](rootPageId, siteId, siteId), rs => {
      while (rs.next()) {
        val categoryId = rs.getString("category_id")
        val anySubCategoryId = Option(rs.getString("sub_category_id"))
        val categoryName = Option(rs.getString("category_name")) getOrElse ""

        if (Some(categoryId) != anyCurrentCategory.map(_.pageId)) {
          // The category is always listed before any sub categories.
          alwaysAssert(anySubCategoryId.isEmpty, "DwE28GI95")

          if (anyCurrentCategory.isDefined) {
            allCategories = allCategories :+ anyCurrentCategory.get
          }
          anyCurrentCategory = Some(Category(categoryName, pageId = categoryId, Vector.empty))
        }
        else {
          alwaysAssert(anySubCategoryId.isDefined, "DwE77Gb91")
          val newSubCategory = Category(categoryName, pageId = anySubCategoryId.get, Vector.empty)
          anyCurrentCategory = anyCurrentCategory map { curCat =>
            curCat.copy(subCategories = curCat.subCategories :+ newSubCategory)
          }
        }
      }
    })

    anyCurrentCategory.foreach { lastCategory =>
      allCategories = allCategories :+ lastCategory
    }

    allCategories
  }


  def movePages(pageIds: Seq[PageId], fromFolder: String, toFolder: String) {
    db.transaction { implicit connection =>
      _movePages(pageIds, fromFolder = fromFolder, toFolder = toFolder)
    }
  }


  private def _movePages(pageIds: Seq[PageId], fromFolder: String,
        toFolder: String)(implicit connection: js.Connection) {
    unimplemented("Moving pages and updating DW1_PAGE_PATHS.CANONICAL")
    /*
    if (pageIds isEmpty)
      return

    // Valid folder paths?
    PagePath.checkPath(folder = fromFolder)
    PagePath.checkPath(folder = toFolder)

    // Escape magic regex chars in folder name — we're using `fromFolder` as a
    // regex. (As of 2012-09-24, a valid folder path contains no regex chars,
    // so this won't restrict which folder names are allowed.)
    if (fromFolder.intersect(MagicRegexChars).nonEmpty)
      illArgErr("DwE93KW18", "Regex chars found in fromFolder: "+ fromFolder)
    val fromFolderEscaped = fromFolder.replace(".", """\.""")

    // Use Postgres' REGEXP_REPLACE to replace only the first occurrance of
    // `fromFolder`.
    val sql = """
      update DW1_PAGE_PATHS
      set PARENT_FOLDER = REGEXP_REPLACE(PARENT_FOLDER, ?, ?)
      where TENANT = ?
        and PAGE_ID in (""" + makeInListFor(pageIds) + ")"
    val values = fromFolderEscaped :: toFolder :: siteId :: pageIds.toList

    db.update(sql, values)
    */
  }


  def moveRenamePage(pageId: PageId,
        newFolder: Option[String], showId: Option[Boolean],
        newSlug: Option[String]): PagePath = {
    db.transaction { implicit connection =>
      moveRenamePageImpl(pageId, newFolder = newFolder, showId = showId,
         newSlug = newSlug)
    }
  }


  override def loadPageParts(pageGuid: PageId, tenantId: Option[SiteId] = None)
        : Option[PageParts] =
    _loadPagePartsAnyTenant(
      tenantId = tenantId getOrElse this.siteId,
      pageId = pageGuid)


  private def _loadPagePartsAnyTenant(tenantId: SiteId, pageId: PageId)
        : Option[PageParts] = {

    val users = loadUsersOnPage(pageId)

    // Load page actions.
    // Order by TIME desc, because when the action list is constructed
    // the order is reversed again.
    db.queryAtnms("""
        select """+ ActionSelectListItems +"""
        from DW1_PAGE_ACTIONS a
        where a.TENANT = ? and a.PAGE_ID = ?
        order by a.TIME desc""",
        List(tenantId, pageId), rs => {
      var actions = List[AnyRef]()
      while (rs.next) {
        val action = _Action(rs)
        actions ::= action  // this reverses above `order by TIME desc'
      }

      if (actions.isEmpty) {
        // BUG Race condition, if page just created in other thread. COULD fix by
        // joining with DW1_PAGES in the query above somehow.
        val anyMeta = loadPageMeta(pageId, anySiteId = Some(tenantId))
        if (anyMeta.isEmpty) {
          // The page doesn't exist.
          return None
        }
      }

      Some(PageParts.fromActions(pageId, this, People(users), actions))
    })
  }


  def loadPageBodiesTitles(pageIds: Seq[PageId]): Map[PageId, PageParts] = {

    if (pageIds isEmpty)
      return Map.empty

    val bodyOrTitle = s"${PageParts.BodyId}, ${PageParts.TitleId}"
    val sql = """
      select a.PAGE_ID, """+ ActionSelectListItems +"""
      from DW1_PAGE_ACTIONS a
      where a.TENANT = ?
        and a.PAGE_ID in ("""+ makeInListFor(pageIds) +""")
        and (
          a.POST_ID in ("""+ bodyOrTitle +""") and
          a.type in (
              'Post', 'Edit', 'EditApp', 'Aprv', 'DelPost', 'DelTree'))"""

    val values = siteId :: pageIds.toList

    val (
      people: People,
      pagesById: mut.Map[PageId, PageParts],
      pageIdsAndActions) =
        _loadPeoplePagesActions(sql, values)

    Map[PageId, PageParts](pagesById.toList: _*)
  }


  def loadPostsRecentlyActive(limit: Int, offset: Int): (List[Post], People) = {
    db.withConnection { implicit connection =>
      var statess = Vector[Vector[(PageId, PostState)]]()
      if (statess.length < limit) statess :+= loadPostStatesPendingFlags(limit, offset)
      if (statess.length < limit) statess :+= loadPostStatesPendingApproval(limit, offset)
      if (statess.length < limit) statess :+= loadPostStatesWithSuggestions(limit, offset)
      if (statess.length < limit) statess :+= loadPostStatesHandled(limit, offset)

      val states = statess.flatten
      val userIds = states.map(_._2.creationAction.userId)

      val people = People(users = loadUsersAsList(userIds.toList))

      val posts = states map { case (pageId, state) =>
        val pageParts = PageParts(pageId, None, people, state::Nil)
        val post = pageParts.getPost_!(state.postId)
        post
      }

      (posts.toList, people)
    }
  }


  def loadFlags(pagePostIds: Seq[PagePostId])
        : (Map[PagePostId, Seq[RawPostAction[PAP.Flag]]], People) = {
    db.withConnection { connection =>
      loadFlagsImpl(pagePostIds)(connection)
    }
  }


  def loadFlagsImpl(pagePostIds: Seq[PagePostId])(implicit connection: js.Connection)
        : (Map[PagePostId, Seq[RawPostAction[PAP.Flag]]], People) = {
    if (pagePostIds isEmpty)
      return (Map.empty, People.None)

    val pagePostIdsSql = {
      val sb = new StringBuilder
      pagePostIds foreach { pagePostId =>
        if (!sb.isEmpty) sb.append(" or ")
        sb.append(s"(PAGE_ID = ? and POST_ID = ?)")
      }
      sb.toString
    }

    val sql = s"""
      select a.PAGE_ID, $ActionSelectListItems
      from DW1_PAGE_ACTIONS a
      where a.TENANT = ?
        and a.type like 'Flag%'
        and ($pagePostIdsSql)"""

    val values: List[AnyRef] = siteId :: pagePostIds.map(_.toList).flatten.toList

    var flagsMap = immutable.HashMap[PagePostId, Vector[RawPostAction[PAP.Flag]]]()
      .withDefaultValue(Vector.empty)

    val flaggers = db.withConnection { implicit connection =>
      db.query(sql, values, rs => {
        while (rs.next) {
          val pageId = rs.getString("PAGE_ID")
          val flag = _Action(rs).asInstanceOf[RawPostAction[PAP.Flag]]
          val pagePostId = PagePostId(pageId, flag.postId)
          var flags = flagsMap(pagePostId)
          flags :+= flag
          flagsMap += pagePostId -> flags
        }
      })

      People(users = loadUsersAsList(flagsMap.values.flatten.map(_.userId).toList.distinct))
    }

    (flagsMap, flaggers)
  }


  def loadRecentActionExcerpts(fromIp: Option[String],
        byRole: Option[UserId],
        pathRanges: PathRanges, limit: Int): (Seq[PostAction[_]], People) = {

    def buildByPersonQuery(fromIp: Option[String],
          byRole: Option[UserId], limit: Int) = {

      // COULD match on browser cookie and/or fingerprint as well.
      val (loginIdsWhereSql, loginIdsWhereValues) =
        if (fromIp isDefined) {
          ("a.IP = ? and a.TENANT = ?", List(fromIp.get, siteId))
        }
        else {
          assErrIf(!User.isRoleId(byRole.get), "DwE27JX1")
          ("a.ROLE_ID = ? and a.TENANT = ?", List(byRole.get, siteId))
        }

      // For now, don't select posts only. We're probably interested in
      // all actions by this user, e.g also his/her ratings, to find
      // out if s/he is astroturfing. (See this function's docs in class Dao.)
      val sql = """
           select a.TENANT, a.PAGE_ID, a.PAID, a.POST_ID
           from DW1_PAGE_ACTIONS a
           where """+ loginIdsWhereSql +"""
           order by a.TIME desc
           limit """+ limit

      // Load things that concerns the selected actions only — not
      // everything that concerns the related posts.
      val whereClause = """
        -- Load actions by login id / folder / page id.
        a.PAID = actionIds.PAID
        -- Load actions that affected [an action by login id]
        -- (e.g. edits, flags, approvals).
          or (
          a.TYPE not like 'Vote%' and -- skip votes
          a.POST_ID = actionIds.POST_ID)"""  // BUG? This finds too many posts:
                      // if `byRole` has voted on a comment, flags on that comment
                      // will be found?

      (sql, loginIdsWhereValues, whereClause)
    }

    def buildByPathQuery(pathRanges: PathRanges, limit: Int) = {
      lazy val (pathRangeClauses, pathRangeValues) =
         _pageRangeToSql(pathRanges, "p.")

      // Select posts only. Edits etc are implicitly selected,
      // later, when actions that affect the selected posts are selected.
      lazy val foldersAndTreesQuery = """
        select a.TENANT, a.PAGE_ID, a.PAID, a.POST_ID
        from
          DW1_PAGE_ACTIONS a inner join DW1_PAGE_PATHS p
          on a.TENANT = p.TENANT and a.PAGE_ID = p.PAGE_ID
            and p.CANONICAL = 'C'
        where
          a.TENANT = ? and ("""+ pathRangeClauses +""")
          and a.TYPE = 'Post'
        order by a.TIME desc
        limit """+ limit

      lazy val foldersAndTreesValues = siteId :: pathRangeValues

      lazy val pageIdsQuery = """
        select a.TENANT, a.PAGE_ID, a.PAID, a.POST_ID
        from DW1_PAGE_ACTIONS a
        where a.TENANT = ?
          and a.PAGE_ID in ("""+
           pathRanges.pageIds.map((x: String) => "?").mkString(",") +""")
          and a.TYPE = 'Post'
        order by a.TIME desc
        limit """+ limit

      lazy val pageIdsValues = siteId :: pathRanges.pageIds.toList

      val (sql, values) = {
        import pathRanges._
        (folders.size + trees.size, pageIds.size) match {
          case (0, 0) => assErr("DwE390XQ2", "No path ranges specified")
          case (0, _) => (pageIdsQuery, pageIdsValues)
          case (_, 0) => (foldersAndTreesQuery, foldersAndTreesValues)
          case (_, _) =>
            // 1. This query might return 2 x limit rows, that's okay for now.
            // 2. `union` elliminates duplicates (`union all` keeps them).
            ("("+ foldersAndTreesQuery +") union ("+ pageIdsQuery +")",
              foldersAndTreesValues ::: pageIdsValues)
        }
      }

      // Load everything that concerns the selected posts, so their current
      // state can be constructed.
      val whereClause = "a.POST_ID = actionIds.POST_ID"

      (sql, values, whereClause)
    }

    val lookupByPerson = fromIp.isDefined || byRole.isDefined
    val lookupByPaths = pathRanges != PathRanges.Anywhere

    // By IP or identity id lookup cannot be combined with by path lookup.
    require(!lookupByPaths || !lookupByPerson)
    // Cannot lookup both by IP and by identity id.
    if (lookupByPerson) require(fromIp.isDefined ^ byRole.isDefined)
    require(0 <= limit)

    val (selectActionIds, values, postIdWhereClause) =
      if (lookupByPerson) buildByPersonQuery(fromIp, byRole, limit)
      else if (lookupByPaths) buildByPathQuery(pathRanges, limit)
      else
        // COULD write more efficient query: don't join with DW1_PAGE_PATHS.
        buildByPathQuery(PathRanges.Anywhere, limit)

    // ((Concerning `distinct` in the `select` below. Without it, [your
    // own actions on your own actions] would be selected twice,
    // because they'd match two rows in actionIds: a.PAID would match
    // (because it's your action) and a.RELPA would match (because the action
    // affected an action of yours). ))
     val sql = s"""
      with actionIds as ($selectActionIds)
      select distinct -- se comment above
         a.PAGE_ID, $ActionSelectListItems
      from DW1_PAGE_ACTIONS a inner join actionIds
         on a.TENANT = actionIds.TENANT
      and a.PAGE_ID = actionIds.PAGE_ID
      and ($postIdWhereClause)
      order by a.TIME desc"""


    val (
      people: People,
      pagesById: mut.Map[PageId, PageParts],
      pageIdsAndActions) =
        _loadPeoplePagesActions(sql, values)

    val pageIdsAndActionsDescTime =
      pageIdsAndActions sortBy { case (_, action) => - action.ctime.getTime }

    def debugDetails = "fromIp: "+ fromIp +", byRole: "+ byRole +
       ", pathRanges: "+ pathRanges

    val smartActions = pageIdsAndActionsDescTime map { case (pageId, action) =>
      val page = pagesById.get(pageId).getOrElse(assErr(
        "DwE9031211", "Page "+ pageId +" missing when loading recent actions, "+
        debugDetails))
      page.getActionById(action.id).getOrDie(
        "DwE85FKA2", s"Action `${action.id}` missing on page `$pageId`")
    }

    (smartActions, people)
  }


  /**
   * Loads People, Pages (Debate:s) and Actions given an SQL statement
   * that selects:
   *   DW1_PAGE_ACTIONS.PAGE_ID and
   *   RdbUtil.ActionSelectListItems.
   */
  private def _loadPeoplePagesActions(
        sql: String, values: List[AnyRef])
        : (People, mut.Map[PageId, PageParts], List[(PageId, RawPostAction[_])]) = {
    val pagesById = mut.Map[PageId, PageParts]()
    var pageIdsAndActions = List[(PageId, RawPostAction[_])]()

    val people = db.withConnection { implicit connection =>
      db.query(sql, values, rs => {
        while (rs.next) {
          val pageId = rs.getString("PAGE_ID")
          val action = _Action(rs)
          val page = pagesById.getOrElseUpdate(pageId, PageParts(pageId))
          val pageWithAction = page ++ (action::Nil)
          pagesById(pageId) = pageWithAction
          pageIdsAndActions ::= pageId -> action
        }
      })

      _loadUsersWhoDid(pageIdsAndActions map (_._2))
    }

    // Load users, so each returned SmartAction supports .user_!.displayName.
    pagesById.transform((pageId, page) => page.copy(people = people))

    (people, pagesById, pageIdsAndActions)
  }


  def loadTenant(): Tenant = {
    systemDaoSpi.loadTenants(List(siteId)).head
    // Should tax quotaConsumer with 2 db IO requests: tenant + tenant hosts.
  }


  def createSite(name: String, hostname: String, embeddingSiteUrl: Option[String],
        creatorIp: String, creatorEmailAddress: String): Tenant = {
    try {
      db.transaction { implicit connection =>
        // Unless apparently testing from localhost, don't allow someone to create
        // very many sites.
        if (creatorIp != LocalhostAddress) {
          val websiteCount = countWebsites(
            createdFromIp = creatorIp, creatorEmailAddress = creatorEmailAddress)
          if (websiteCount >= MaxWebsitesPerIp)
            throw TooManySitesCreatedException(creatorIp)
        }

        val newTenantNoId = Tenant(id = "?", name = Some(name), creatorIp = creatorIp,
          creatorEmailAddress = creatorEmailAddress, embeddingSiteUrl = embeddingSiteUrl,
          hosts = Nil)
        val newTenant = insertSite(newTenantNoId)
        val newHost = TenantHost(hostname, TenantHost.RoleCanonical, TenantHost.HttpsNone)
        val newHostCount = systemDaoSpi.insertTenantHost(newTenant.id, newHost)(connection)
        assErrIf(newHostCount != 1, "DwE09KRF3")
        newTenant.copy(hosts = List(newHost))
      }
    }
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex)) throw ex
        throw new SiteAlreadyExistsException(name)
    }
  }


  private def countWebsites(createdFromIp: String, creatorEmailAddress: String)
        (implicit connection: js.Connection): Int = {
    db.query("""
        select count(*) WEBSITE_COUNT from DW1_TENANTS
        where CREATOR_IP = ? or CREATOR_EMAIL_ADDRESS = ?
        """, createdFromIp::creatorEmailAddress::Nil, rs => {
      rs.next()
      val websiteCount = rs.getInt("WEBSITE_COUNT")
      websiteCount
    })
  }


  private def insertSite(tenantNoId: Tenant)
        (implicit connection: js.Connection): Tenant = {
    assErrIf(tenantNoId.id != "?", "DwE91KB2")
    val tenant = tenantNoId.copy(
      id = db.nextSeqNo("DW1_TENANTS_ID").toString)
    db.update("""
        insert into DW1_TENANTS (
          ID, NAME, EMBEDDING_SITE_URL, CREATOR_IP, CREATOR_EMAIL_ADDRESS)
        values (?, ?, ?, ?, ?)""",
      List[AnyRef](tenant.id, tenant.name.orNullVarchar,
        tenant.embeddingSiteUrl.orNullVarchar, tenant.creatorIp,
        tenant.creatorEmailAddress))
    tenant
  }


  def addTenantHost(host: TenantHost) = {
    db.transaction { implicit connection =>
      systemDaoSpi.insertTenantHost(siteId, host)(connection)
    }
  }


  def lookupOtherTenant(scheme: String, host: String): TenantLookup = {
    systemDaoSpi.lookupTenant(scheme, host)
  }


  def checkPagePath(pathToCheck: PagePath): Option[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def listPagePaths(
    pageRanges: PathRanges,
    includeStatuses: List[PageStatus],
    orderOffset: PageOrderOffset,
    limit: Int): Seq[PagePathAndMeta] = {

    require(1 <= limit)
    require(pageRanges.pageIds isEmpty) // for now

    val statusesToInclStr =
      includeStatuses.map(_toFlag).mkString("'", "','", "'")
    if (statusesToInclStr isEmpty)
      return Nil

    val orderByStr = orderOffset match {
      case PageOrderOffset.ByPath =>
        " order by t.PARENT_FOLDER, t.SHOW_ID, t.PAGE_SLUG"
      case PageOrderOffset.ByPublTime =>
        // For now: (CACHED_PUBL_TIME not implemented)
        " order by t.CDATI desc"
      case _ =>
        // now I've added a few new sort orders, but this function is
        // hardly used any more anyway, so don't impnement.
        unimplemented("DwE582WR0")
    }

    val (pageRangeClauses, pageRangeValues) = _pageRangeToSql(pageRanges)

    val filterStatusClauses =
      if (includeStatuses.contains(PageStatus.Draft)) "true"
      else "g.PUBL_DATI is not null"

    val values = siteId :: pageRangeValues
    val sql = s"""
        select t.PARENT_FOLDER,
            t.PAGE_ID,
            t.SHOW_ID,
            t.PAGE_SLUG,
            ${_PageMetaSelectListItems}
        from DW1_PAGE_PATHS t left join DW1_PAGES g
          on t.TENANT = g.TENANT and t.PAGE_ID = g.GUID
        where t.CANONICAL = 'C'
          and t.TENANT = ?
          and ($pageRangeClauses)
          and ($filterStatusClauses)
          and g.PAGE_ROLE <> 'SP' -- skip Special Content
        $orderByStr
        limit $limit"""

    var items = List[PagePathAndMeta]()

    db.withConnection { implicit connection =>
     db.query(sql, values, rs => {
      while (rs.next) {
        val pagePath = _PagePath(rs, siteId)
        val pageMeta = _PageMeta(rs, pagePath.pageId.get)

        // This might be too inefficient if there are many pages:
        // (But need load ancestor ids, for access control — some ancestor page might
        // be private. COULD rewrite and use batchLoadAncestorIdsParentFirst() instead.
        val ancestorIds = loadAncestorIdsParentFirstImpl(pageMeta.pageId)(connection)

        items ::= PagePathAndMeta(pagePath, ancestorIds, pageMeta)
      }
     })
    }
    items.reverse
  }


  def listChildPages(parentPageIds: Seq[PageId], orderOffset: PageOrderOffset,
        limit: Int, filterPageRole: Option[PageRole] = None)
        : Seq[PagePathAndMeta] = {

    require(1 <= limit)
    var values = Vector[AnyRef]()

    val (orderByStr, offsetTestAnd) = orderOffset match {
      case PageOrderOffset.Any =>
        ("", "")
      case PageOrderOffset.ByPublTime =>
        ("order by g.PUBL_DATI desc", "")
      case PageOrderOffset.ByBumpTime(anyDate) =>
        val offsetTestAnd = anyDate match {
          case None => ""
          case Some(date) =>
            values :+= d2ts(date)
            "g.CACHED_LAST_VISIBLE_POST_DATI <= ? and"
        }
        ("order by g.CACHED_LAST_VISIBLE_POST_DATI desc", offsetTestAnd)
      case PageOrderOffset.ByLikesAndBumpTime(anyLikesAndDate) =>
        val offsetTestAnd = anyLikesAndDate match {
          case None => ""
          case Some((maxNumLikes, date)) =>
            values :+= maxNumLikes.asAnyRef
            values :+= d2ts(date)
            values :+= maxNumLikes.asAnyRef
            """((g.CACHED_NUM_LIKES <= ? and g.CACHED_LAST_VISIBLE_POST_DATI <= ?) or
                (g.CACHED_NUM_LIKES < ?)) and"""
        }
        ("order by g.CACHED_NUM_LIKES desc, CACHED_LAST_VISIBLE_POST_DATI desc", offsetTestAnd)
      case _ =>
        unimplemented(s"Sort order unsupported: $orderOffset [DwE2GFU06]")
    }

    values :+= siteId

    val pageRoleTestAnd = filterPageRole match {
      case Some(pageRole) =>
        illArgIf(pageRole == PageRole.WebPage, "DwE20kIR8")
        values :+= _pageRoleToSql(pageRole)
        "g.PAGE_ROLE = ? and"
      case None => ""
    }

    values = values ++ parentPageIds

    val sql = s"""
        select t.PARENT_FOLDER,
            t.PAGE_ID,
            t.SHOW_ID,
            t.PAGE_SLUG,
            ${_PageMetaSelectListItems}
        from DW1_PAGES g left join DW1_PAGE_PATHS t
          on g.TENANT = t.TENANT and g.GUID = t.PAGE_ID
          and t.CANONICAL = 'C'
        where
          $offsetTestAnd
          g.TENANT = ? and
          $pageRoleTestAnd
          g.PARENT_PAGE_ID in (${ makeInListFor(parentPageIds) })
        $orderByStr
        limit $limit"""

    var items = List[PagePathAndMeta]()

    db.withConnection { implicit connection =>

      val parentsAncestorsByParentId: collection.Map[PageId, List[PageId]] =
        batchLoadAncestorIdsParentFirst(parentPageIds.toList)(connection)

      db.query(sql, values.toList, rs => {
        while (rs.next) {
          val pagePath = _PagePath(rs, siteId)
          val pageMeta = _PageMeta(rs, pagePath.pageId.get)

          val parentsAncestors =
            parentsAncestorsByParentId.get(pageMeta.parentPageId.get) getOrElse Nil
          val ancestorIds = pageMeta.parentPageId.get :: parentsAncestors

          items ::= PagePathAndMeta(pagePath, ancestorIds, pageMeta)
        }
      })
    }
    items.reverse
  }


  def loadPermsOnPage(reqInfo: PermsOnPageQuery): PermsOnPage = {
    // Currently all permissions are actually hardcoded in this function.
    // (There's no permissions db table.)

    /*
    The algorithm: (a sketch. And not yet implemented)
    lookup rules in PATHRULES:  (not implemented! paths hardcoded instead)
      if guid, try:  parentFolder / -* /   (i.e. any guid in folder)
      else, try:
        first: parentFolder / pageName /   (this particular page)
        then:  parentFolder / * /          (any page in folder)
      Then continue with the parent folders:
        first: parentsParent / parentFolderName /
        then: parentsParent / * /
      and so on with the parent's parent ...
    */

    // ?? Replace admin test with:
    // if (requeuster.memships.contains(AdminGroupId)) return PermsOnPage.All

    // Allow admins to do anything, e.g. create pages anywhere.
    // (Currently users can edit their own pages only.)
    if (reqInfo.user.map(_.isAdmin) == Some(true))
      return PermsOnPage.All

    // Files whose name starts with '_' are hidden, only admins have access.
    if (reqInfo.pagePath.isHiddenPage)
      return PermsOnPage.None

    // People may view and use Javascript and CSS, but of course not edit it.
    if (reqInfo.pagePath.isScriptOrStyle)
      return PermsOnPage.None.copy(accessPage = true)

    // For now, hardcode rules here:
    val mayCreatePage = {
      val p = reqInfo.pagePath.value
      if (p startsWith "/test/") true
      else if (p startsWith "/forum/") true
      else if (p startsWith "/wiki/") true
      else false
    }

    val isPageAuthor =
      (for (user <- reqInfo.user; pageMeta <- reqInfo.pageMeta) yield {
        user.id == pageMeta.cachedAuthorUserId
      }) getOrElse false


    val isWiki = reqInfo.pagePath.folder == "/wiki/"

    PermsOnPage.None.copy(
      accessPage = true,
      editUnauReply = true,
      createPage = mayCreatePage,
      editPage = isWiki || isPageAuthor,
      // Authenticated users can edit others' comments.
      //  — no, disable this for now, seems too dangerous.
      //    Instead I should perhaps have the AutoApprover check the user's
      //    past actions, and only sometimes automatically approve edits.
      // (In the future, the reputation system (not implemented) will make
      // them lose this ability should they misuse it.)
      editAnyReply = isWiki, // || reqInfo.user.map(_.isAuthenticated) == Some(true)
      pinReplies = isWiki || isPageAuthor)
  }


  def saveUnsentEmailConnectToNotfs(email: Email, notfs: Seq[Notification]) {
    db.transaction { implicit connection =>
      _saveUnsentEmail(email)
      updateNotificationConnectToEmail(notfs, Some(email))
    }
  }


  def saveUnsentEmail(email: Email) {
    db.transaction { _saveUnsentEmail(email)(_) }
  }


  private def _saveUnsentEmail(email: Email)
        (implicit connection: js.Connection) {

    require(email.id != "?")
    require(email.failureText isEmpty)
    require(email.providerEmailId isEmpty)
    require(email.sentOn isEmpty)

    def emailTypeToString(tyype: EmailType) = tyype match {
      case EmailType.Notification => "Notf"
      case EmailType.CreateAccount => "CrAc"
      case EmailType.ResetPassword => "RsPw"
    }

    val vals = List(siteId, email.id, emailTypeToString(email.tyype), email.sentTo,
      email.toGuestId.orNullVarchar, email.toRoleId.orNullVarchar,
      d2ts(email.createdAt), email.subject, email.bodyHtmlText)

    db.update("""
      insert into DW1_EMAILS_OUT(
        TENANT, ID, TYPE, SENT_TO, TO_GUEST_ID, TO_ROLE_ID, CREATED_AT, SUBJECT, BODY_HTML)
      values (
        ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """, vals)
  }


  def updateSentEmail(email: Email) {
    db.transaction { implicit connection =>

      val sentOn = email.sentOn.map(d2ts(_)) getOrElse NullTimestamp
      // 'O' means Other, use for now.
      val failureType = email.failureText.isDefined ?
         ("O": AnyRef) | (NullVarchar: AnyRef)
      val failureTime = email.failureText.isDefined ?
         (sentOn: AnyRef) | (NullTimestamp: AnyRef)

      val vals = List(
        sentOn, email.providerEmailId.orNullVarchar,
        failureType, email.failureText.orNullVarchar, failureTime,
        siteId, email.id)

      db.update("""
        update DW1_EMAILS_OUT
        set SENT_ON = ?, PROVIDER_EMAIL_ID = ?,
            FAILURE_TYPE = ?, FAILURE_TEXT = ?, FAILURE_TIME = ?
        where TENANT = ? and ID = ?
        """, vals)
    }
  }


  def loadEmailById(emailId: String): Option[Email] = {
    val query = """
      select TYPE, SENT_TO, TO_GUEST_ID, TO_ROLE_ID, SENT_ON, CREATED_AT, SUBJECT,
        BODY_HTML, PROVIDER_EMAIL_ID, FAILURE_TEXT
      from DW1_EMAILS_OUT
      where TENANT = ? and ID = ?
      """
    val emailOpt = db.queryAtnms(query, List(siteId, emailId), rs => {
      var allEmails = List[Email]()
      while (rs.next) {
        def parseEmailType(typeString: String) = typeString match {
          case "Notf" => EmailType.Notification
          case "CrAc" => EmailType.CreateAccount
          case "RsPw" => EmailType.ResetPassword
          case _ => throwBadDatabaseData(
            "DwE840FSIE", s"Bad email type: $typeString, email id: $emailId")
            EmailType.Notification
        }

        val anyGuestId = Option(rs.getString("TO_GUEST_ID"))
        val anyRoleId = Option(rs.getString("TO_ROLE_ID"))
        val toUserId: Option[UserId] = anyRoleId.orElse(anyGuestId.map("-" + _))

        val email = Email(
           id = emailId,
           tyype = parseEmailType(rs.getString("TYPE")),
           sentTo = rs.getString("SENT_TO"),
           toUserId = toUserId,
           sentOn = Option(ts2d(rs.getTimestamp("SENT_ON"))),
           createdAt = ts2d(rs.getTimestamp("CREATED_AT")),
           subject = rs.getString("SUBJECT"),
           bodyHtmlText = rs.getString("BODY_HTML"),
           providerEmailId = Option(rs.getString("PROVIDER_EMAIL_ID")),
           failureText = Option(rs.getString("FAILURE_TEXT")))
        allEmails = email::allEmails
      }
      assert(allEmails.length <= 1) // loaded by PK
      allEmails.headOption
    })

    emailOpt
  }


  def lookupPagePath(pageId: PageId): Option[PagePath] =
    lookupPagePathImpl(pageId)(null)


  def lookupPagePathImpl(pageId: PageId)(implicit connection: js.Connection)
        : Option[PagePath] =
    lookupPagePathsImpl(pageId, loadRedirects = false).headOption


  def lookupPagePathAndRedirects(pageId: PageId): List[PagePath] =
    lookupPagePathsImpl(pageId, loadRedirects = true)(null)


  private def lookupPagePathsImpl(pageId: PageId, loadRedirects: Boolean)
        (implicit connection: js.Connection)
        : List[PagePath] = {

    val andOnlyCanonical = if (loadRedirects) "" else "and CANONICAL = 'C'"
    val values = List(siteId, pageId)
    val sql = s"""
      select PARENT_FOLDER, SHOW_ID, PAGE_SLUG,
        -- For debug assertions:
        CANONICAL, CANONICAL_DATI
      from DW1_PAGE_PATHS
      where TENANT = ? and PAGE_ID = ? $andOnlyCanonical
      order by $CanonicalLast, CANONICAL_DATI asc"""

    var pagePaths = List[PagePath]()

    db.query(sql, values, rs => {
      var debugLastIsCanonical = false
      var debugLastCanonicalDati = new ju.Date(0)
      while (rs.next) {
        // Assert that there are no sort order bugs.
        assert(!debugLastIsCanonical)
        debugLastIsCanonical = rs.getString("CANONICAL") == "C"
        val canonicalDati = ts2d(rs.getTimestamp("CANONICAL_DATI"))
        assert(canonicalDati.getTime > debugLastCanonicalDati.getTime)
        debugLastCanonicalDati = canonicalDati

        pagePaths ::= _PagePath(rs, siteId, pageId = Some(Some(pageId)))
      }
      assert(debugLastIsCanonical || pagePaths.isEmpty)
    })

    pagePaths
  }


  // Sort order that places the canonical row first.
  // ('C'anonical is before 'R'edirect.)
  val CanonicalFirst = "CANONICAL asc"

  val CanonicalLast = "CANONICAL desc"


  // Looks up the correct PagePath for a possibly incorrect PagePath.
  private def _findCorrectPagePath(pagePathIn: PagePath)
      (implicit connection: js.Connection = null): Option[PagePath] = {

    var query = """
        select PARENT_FOLDER, PAGE_ID, SHOW_ID, PAGE_SLUG, CANONICAL
        from DW1_PAGE_PATHS
        where TENANT = ?
        """

    var binds = List(pagePathIn.tenantId)
    pagePathIn.pageId match {
      case Some(id) =>
        query += s" and PAGE_ID = ? order by $CanonicalFirst"
        binds ::= id
      case None =>
        // SHOW_ID = 'F' means that the page guid must not be part
        // of the page url. ((So you cannot look up [a page that has its guid
        // as part of its url] by searching for its url without including
        // the guid. Had that been possible, many pages could have been found
        // since pages with different guids can have the same name.
        // Hmm, could search for all pages, as if the guid hadn't been
        // part of their name, and list all pages with matching names?))
        query += """
            and SHOW_ID = 'F'
            and (
              (PARENT_FOLDER = ? and PAGE_SLUG = ?)
            """
        binds ::= pagePathIn.folder
        binds ::= e2d(pagePathIn.pageSlug)
        // Try to correct bad URL links.
        // COULD skip (some of) the two if tests below, if action is ?newpage.
        // (Otherwise you won't be able to create a page in
        // /some/path/ if /some/path already exists.)
        if (pagePathIn.pageSlug nonEmpty) {
          // Perhaps the correct path is /folder/page/ not /folder/page.
          // Try that path too. Choose sort orter so /folder/page appears
          // first, and skip /folder/page/ if /folder/page is found.
          query += s"""
              or (PARENT_FOLDER = ? and PAGE_SLUG = '-')
              )
            order by length(PARENT_FOLDER) asc, $CanonicalFirst
            """
          binds ::= pagePathIn.folder + pagePathIn.pageSlug +"/"
        }
        else if (pagePathIn.folder.count(_ == '/') >= 2) {
          // Perhaps the correct path is /folder/page not /folder/page/.
          // But prefer /folder/page/ if both pages are found.
          query += s"""
              or (PARENT_FOLDER = ? and PAGE_SLUG = ?)
              )
            order by length(PARENT_FOLDER) desc, $CanonicalFirst
            """
          val perhapsPath = pagePathIn.folder.dropRight(1)  // drop `/'
          val lastSlash = perhapsPath.lastIndexOf("/")
          val (shorterPath, nonEmptyName) = perhapsPath.splitAt(lastSlash + 1)
          binds ::= shorterPath
          binds ::= nonEmptyName
        }
        else {
          query += s"""
              )
            order by $CanonicalFirst
            """
        }
    }

    val (correctPath: PagePath, isCanonical: Boolean) =
      db.query(query, binds.reverse, rs => {
        if (!rs.next)
          return None
        var correctPath = PagePath(
            tenantId = pagePathIn.tenantId,
            folder = rs.getString("PARENT_FOLDER"),
            pageId = Some(rs.getString("PAGE_ID")),
            showId = rs.getString("SHOW_ID") == "T",
            // If there is a root page ("serveraddr/") with no name,
            // it is stored as a single space; s2e removes such a space:
            pageSlug = d2e(rs.getString("PAGE_SLUG")))
        val isCanonical = rs.getString("CANONICAL") == "C"
        (correctPath, isCanonical)
      })

    if (!isCanonical) {
      // We've found a page path that's been inactivated and therefore should
      // redirect to the currently active path to the page. Find that other
      // path (the canonical path), by page id.
      runErrIf3(correctPath.pageId.isEmpty,
        "DwE31Rg5", s"Page id not found when looking up $pagePathIn")
      return _findCorrectPagePath(correctPath)
    }

    Some(correctPath)
  }


  private def _createPage[T](page: Page)(implicit conn: js.Connection) {
    require(page.meta.creationDati == page.meta.modDati)
    page.meta.pubDati.foreach(publDati =>
      require(page.meta.creationDati.getTime <= publDati.getTime))

    val sql = """
      insert into DW1_PAGES (
         SNO, TENANT, GUID, PAGE_ROLE, PARENT_PAGE_ID, EMBEDDING_PAGE_URL,
         CDATI, MDATI, PUBL_DATI)
      values (
         nextval('DW1_PAGES_SNO'), ?, ?, ?, ?, ?, ?, ?, ?)
      """

    val values = List[AnyRef](page.tenantId, page.id,
      _pageRoleToSql(page.role), page.parentPageId.orNullVarchar,
      page.meta.embeddingPageUrl.orNullVarchar,
      d2ts(page.meta.creationDati), d2ts(page.meta.modDati),
      page.meta.pubDati.map(d2ts _).getOrElse(NullTimestamp))

    val numNewRows = db.update(sql, values)

    if (numNewRows == 0) {
      // If the problem was a primary key violation, we wouldn't get to here.
      runErr("DwE48GS3", o"""Cannot create a `${page.role}' page because
        the parent page, id `${page.parentPageId}', has an incompatible role""")
    }

    if (2 <= numNewRows)
      assErr("DwE45UL8") // there's a primary key on site + page id

    _updatePageMeta(page.meta, anyOld = None)
    insertPagePathOrThrow(page.path)
  }


  private def insertPagePathOrThrow(pagePath: PagePath)(
        implicit conn: js.Connection) {
    illArgErrIf3(pagePath.pageId.isEmpty, "DwE21UY9", s"No page id: $pagePath")
    val showPageId = pagePath.showId ? "T" | "F"
    try {
      db.update("""
        insert into DW1_PAGE_PATHS (
          TENANT, PARENT_FOLDER, PAGE_ID, SHOW_ID, PAGE_SLUG, CANONICAL)
        values (?, ?, ?, ?, ?, 'C')
        """,
        List(pagePath.tenantId, pagePath.folder, pagePath.pageId.get,
          showPageId, e2d(pagePath.pageSlug)))
    }
    catch {
      case ex: js.SQLException if (isUniqueConstrViolation(ex)) =>
        val mess = ex.getMessage.toUpperCase
        if (mess.contains("DW1_PGPTHS_PATH_NOID_CNCL__U")) {
          // There's already a page path where we attempt to insert
          // the new path.
          throw PathClashException(
            pagePath.copy(pageId = None), newPagePath = pagePath)
        }
        if (ex.getMessage.contains("DW1_PGPTHS_TNT_PGID_CNCL__U")) {
          // Race condition. Another session just moved this page, that is,
          // inserted a new 'C'anonical row. There must be only one such row.
          // This probably means that two admins attempted to move pageId
          // at the same time (or that longer page ids should be generated).
          // Details:
          // 1. `moveRenamePageImpl` deletes any 'C'anonical rows before
          //  it inserts a new 'C'anonical row, so unless another session
          //  does the same thing inbetween, this error shouldn't happen.
          // 2 When creating new pages: Page ids are generated randomly,
          //  and are fairly long, unlikely to clash.
          throw new ju.ConcurrentModificationException(
            s"Another administrator/moderator apparently just added a path" +
            s" to this page: ${pagePath.value}, id `${pagePath.pageId.get}'." +
            s" (Or the server needs to generate longer page ids.)")
        }
        throw ex
    }
  }


  private def updateParentPageChildCount(parentId: PageId, change: Int)
        (implicit conn: js.Connection) {
    require(change == 1 || change == -1)
    val sql = i"""
      |update DW1_PAGES
      |set CACHED_NUM_CHILD_PAGES = CACHED_NUM_CHILD_PAGES + ($change)
      |where TENANT = ? and GUID = ?
      """
    val values = List(siteId, parentId)
    val rowsUpdated = db.update(sql, values)
    assErrIf(rowsUpdated != 1, "DwE70BK12")
  }


  def movePageToItsPreviousLocation(pagePath: PagePath): Option[PagePath] = {
    db.transaction { implicit connection =>
      movePageToItsPreviousLocationImpl(pagePath)
    }
  }


  def movePageToItsPreviousLocationImpl(pagePath: PagePath)(
        implicit connection: js.Connection): Option[PagePath] = {
    val pageId = pagePath.pageId getOrElse {
      _findCorrectPagePath(pagePath).flatMap(_.pageId).getOrElse(
        throw PageNotFoundByPathException(pagePath))
    }
    val allPathsToPage = lookupPagePathsImpl(pageId, loadRedirects = true)
    if (allPathsToPage.length < 2)
      return None
    val allRedirects = allPathsToPage.tail
    val mostRecentRedirect = allRedirects.head
    moveRenamePageImpl(mostRecentRedirect)
    Some(mostRecentRedirect)
  }


  private def moveRenamePageImpl(pageId: PageId,
        newFolder: Option[String], showId: Option[Boolean],
        newSlug: Option[String])
        (implicit conn: js.Connection): PagePath = {

    // Verify new path is legal.
    PagePath.checkPath(tenantId = siteId, pageId = Some(pageId),
      folder = newFolder getOrElse "/", pageSlug = newSlug getOrElse "")

    val currentPath: PagePath = lookupPagePathImpl(pageId) getOrElse (
          throw PageNotFoundByIdException(siteId, pageId))

    val newPath = {
      var path = currentPath
      newFolder foreach { folder => path = path.copy(folder = folder) }
      showId foreach { show => path = path.copy(showId = show) }
      newSlug foreach { slug => path = path.copy(pageSlug = slug) }
      path
    }

    moveRenamePageImpl(newPath)

    val resultingPath = lookupPagePathImpl(pageId)
    runErrIf3(resultingPath != Some(newPath),
      "DwE31ZB0", s"Resulting path: $resultingPath, and intended path: " +
        s"$newPath, are different")

    newPath
  }


  def moveRenamePage(newPath: PagePath) {
    db.transaction { implicit connection =>
      moveRenamePageImpl(newPath)
    }
  }


  private def moveRenamePageImpl(newPath: PagePath)
        (implicit conn: js.Connection) {

    val pageId = newPath.pageId getOrElse
      illArgErr("DwE37KZ2", s"Page id missing: $newPath")

    // Lets do this:
    // 1. Set all current paths to pageId to CANONICAL = 'R'edirect
    // 2. Delete any 'R'edirecting path that clashes with the new path
    //    we're about to save (also if it points to pageId).
    // 3. Insert the new path.
    // 4. If the insertion fails, abort; this means we tried to overwrite
    //    a 'C'anonical path to another page (which we shouldn't do,
    //    because then that page would no longer be reachable).

    def changeExistingPathsToRedirects(pageId: PageId) {
      val vals = List(siteId, pageId)
      val stmt = """
        update DW1_PAGE_PATHS
        set CANONICAL = 'R'
        where TENANT = ? and PAGE_ID = ?
        """
      val numRowsChanged = db.update(stmt, vals)
      if (numRowsChanged == 0)
        throw PageNotFoundByIdException(siteId, pageId, details = Some(
          "It seems all paths to the page were deleted moments ago"))
    }

    def deleteAnyExistingRedirectFrom(newPath: PagePath) {
      val showPageId = newPath.showId ? "T" | "F"
      var vals = List(siteId, newPath.folder, e2d(newPath.pageSlug), showPageId)
      var stmt = """
        delete from DW1_PAGE_PATHS
        where TENANT = ? and PARENT_FOLDER = ? and PAGE_SLUG = ?
          and SHOW_ID = ? and CANONICAL = 'R'
        """
      if (newPath.showId) {
        // We'll find at most one row, and it'd be similar to the one
        // we intend to insert, except for 'R'edirect not 'C'anonical.
        stmt = stmt + " and PAGE_ID = ?"
        vals = vals ::: List(pageId)
      }
      val numRowsDeleted = db.update(stmt, vals)
      assErrIf(1 < numRowsDeleted && newPath.showId, "DwE09Ij7")
    }

    changeExistingPathsToRedirects(pageId)
    deleteAnyExistingRedirectFrom(newPath)
    insertPagePathOrThrow(newPath)
  }


  override def doSavePageActions(page: PageNoPath, actions: List[RawPostAction[_]])
      : (PageNoPath, List[RawPostAction[_]]) = {
    // Try many times, because harmless deadlocks might abort the first attempt.
    // Example: Editing a row with a foreign key to table R result in a shared lock
    // on the referenced row in table R — so if two sessions A and B insert rows for
    // the same page into DW1_PAGE_ACTIONS and then update DW1_PAGES aftewards,
    // the update statement from session A blocks on the shared lock that
    // B holds on the DW1_PAGES row, and then session B blocks on the exclusive
    // lock on the DW1_PAGES row that A's update statement is trying to grab.
    // An E2E test that fails without `tryManyTimes` here is `EditActivitySpec`.
    tryManyTimes(2) {
      db.transaction { implicit connection =>
        savePageActionsImpl(page, actions)
      }
    }
  }


  private def savePageActionsImpl(
        page: PageNoPath, actions: List[RawPostAction[_]])(
        implicit conn: js.Connection):  (PageNoPath, List[RawPostAction[_]]) = {

    // Save new actions.
    val actionsWithIds = insertActions(page.id, actions)

    // Update cached post states (e.g. the current text of a comment).
    val newParts = page.parts ++ actionsWithIds
    val postIds = actionsWithIds.map(_.postId).distinct
    val posts =
      for (postId <- postIds)
      yield newParts.getPost(postId) getOrDie "DwE70Bf8"

    // Weird comment: (we already have the RawPostAction's here!?)
    // If a rating implies nearby posts are to be considered read,
    // we need the actual RawPostAction's here, not only post ids — so we
    // can update the read counts of all affected posts.
    posts foreach { post =>
      // Doesn't totally work (!). Uses page.parts which was loaded *before*
      // the actions were inserted, and does thus not take e.g. cleared flags
      // into account (it thinks all flags are still pending, event if a ClearFlags
      // action has been inserted). However, we call updateAffectedThings() below which
      // correctly updates everything anyway.
      insertUpdatePost(post)(conn)
    }

    // Update e.g. posts that were hidden/shown or post/flags/pages that were deleted
    // or undeleted, so e.g. their DELETED_AT/BY_ID columns have correct values.
    updateAffectedThings(page.id, actionsWithIds)(conn)

    // Update cached page meta (e.g. the page title).
    val newMeta = PageMeta.forChangedPage(page.meta, newParts)
    if (newMeta != page.meta)
      _updatePageMeta(newMeta, anyOld = Some(page.meta))

    // Index the posts for full text search as soon as possible.
    if (!daoFactory.fastStartSkipSearch)
      fullTextSearchIndexer.indexNewPostsSoon(page, posts, siteId)

    (PageNoPath(newParts, page.ancestorIdsParentFirst, newMeta), actionsWithIds.toList)
  }


  private def insertActions(pageId: PageId, actions: Seq[RawPostAction[_]])
        (implicit conn: js.Connection): Seq[RawPostAction[_]] = {
    val numNewReplies = actions.filter(PageParts.isReply _).size

    val nextNewReplyId =
      if (numNewReplies == 0) -1
      else {
        // I'd rather avoid stored functions, but UPDATE ... RETURNING ... INTO
        // otherwise fails: This:
        //   {call update DW1_PAGES
        //     set NEXT_REPLY_ID = NEXT_REPLY_ID + ?
        //     where TENANT = ? and GUID = ?
        //     returning NEXT_REPLY_ID into ?  }
        // results in an error: """PSQLException: ERROR: syntax error at or near "set" """
        // with no further details. So instead use a stored function:
        val sql = "{? = call INC_NEXT_PER_PAGE_REPLY_ID(?, ?, ?) }"
        val values = List(siteId, pageId, numNewReplies.asInstanceOf[AnyRef])
        var nextNewIdAfterwards = -1
        db.call(sql, values, js.Types.INTEGER, result => {
          nextNewIdAfterwards = result.getInt(1)
        })
        nextNewIdAfterwards - numNewReplies
      }

    val actionsWithIds = PageParts.assignIdsTo(actions, nextNewReplyId)
    for (action <- actionsWithIds) {
      // Could optimize:  (but really *not* important!)
      // Use a CallableStatement and `insert into ... returning ...'
      // to create the _ACTIONS row and read the SNO in one single roundtrip.
      // Or select many SNO:s in one single query? and then do a batch
      // insert into _ACTIONS (instead of one insert per row), and then
      // batch inserts into other tables e.g. _RATINGS.

      val insertIntoActions = """
          insert into DW1_PAGE_ACTIONS(
            GUEST_ID, ROLE_ID, IP,
            BROWSER_ID_COOKIE, BROWSER_FINGERPRINT,
            TENANT, PAGE_ID, POST_ID, PAID, TIME,
            TYPE, RELPA, TEXT, LONG_VALUE, WHEERE,
            MULTIREPLY, APPROVAL, AUTO_APPLICATION)
          values (
            ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?)"""

      // Set the user id and ip to null for the system user. (The user id has to
      // be null beause there's no matching row in the roles table.)
      val (ipNullForSystem, roleIdNullForSystem, browserFingerprintNullForSystem) =
        if (action.userId == SystemUser.User.id) {
          assErrIf(action.ip != SystemUser.Ip, "DwE8SW05")
          (NullVarchar, NullVarchar, NullInt)
        }
        else {
          (action.ip, action.anyRoleId.orNullVarchar, action.browserFingerprint.asAnyRef)
        }

      val guestIdNullForUnknown =
        if (action.userId == UnknownUser.Id)
          NullVarchar
        else
          action.anyGuestId.orNullVarchar

      // Keep in mind that Oracle converts "" to null.
      val commonVals: List[AnyRef] = List(
        guestIdNullForUnknown,
        roleIdNullForSystem,
        ipNullForSystem,
        action.browserIdCookie.orNullVarchar,
        browserFingerprintNullForSystem,
        siteId,
        pageId,
        action.postId.asAnyRef,
        action.id.asAnyRef,
        d2ts(action.ctime))

      // TODO fix indentation
          def insertSimpleValue(tyype: String) =
            db.update(insertIntoActions, commonVals:::List(
              tyype, action.postId.asAnyRef, NullVarchar, NullInt, NullVarchar, NullVarchar,
              NullVarchar, NullVarchar))

      def tryInsertVote(voteString: String) {
        try {
          insertSimpleValue(voteString)
        }
        catch {
          case ex: js.SQLException if isUniqueConstrViolation(ex) =>
            throw DbDao.DuplicateVoteException
        }
      }

          val PAP = PostActionPayload
          action.payload match {
            case p: PAP.CreatePost =>
              db.update(insertIntoActions, commonVals:::List(
                "Post", p.parentPostId.orNullInt, e2n(p.text), NullInt,
                e2n(p.where), toDbMultireply(p.multireplyPostIds),
                _toDbVal(p.approval), NullVarchar))
            case e: PAP.EditPost =>
              val autoAppliedDbVal = if (e.autoApplied) "A" else NullVarchar
              db.update(insertIntoActions, commonVals:::List(
                "Edit", NullInt, e2n(e.text), NullInt, NullVarchar,
                NullVarchar, _toDbVal(e.approval), autoAppliedDbVal))
            case a: PAP.EditApp =>
              db.update(insertIntoActions, commonVals:::List(
                "EditApp", a.editId.asAnyRef, NullVarchar, NullInt,
                NullVarchar, NullVarchar, _toDbVal(a.approval), NullVarchar))
            case r: PAP.ApprovePost =>
              val tyype = "Aprv"
              db.update(insertIntoActions, commonVals:::List(
                tyype, NullInt, NullVarchar, NullInt, NullVarchar,
                NullVarchar, _toDbVal(Some(r.approval)), NullVarchar))
            case p: PAP.PinPostAtPosition =>
              db.update(insertIntoActions, commonVals:::List(
                "PinAtPos", NullInt, NullVarchar, p.position.asAnyRef,
                NullVarchar, NullVarchar, NullVarchar, NullVarchar))
            case f: PAP.Flag =>
              db.update(insertIntoActions, commonVals:::List(
                "Flag" + f.tyype,
                NullInt, e2n(f.reason), NullInt, NullVarchar,
                NullVarchar, NullVarchar, NullVarchar))

            case PAP.VoteLike => tryInsertVote("VoteLike")
            case PAP.VoteWrong => tryInsertVote("VoteWrong")
            case PAP.VoteOffTopic => tryInsertVote("VoteOffTopic")
            case PAP.CollapsePost => insertSimpleValue("CollapsePost")
            case PAP.CollapseTree => insertSimpleValue("CollapseTree")
            case PAP.CloseTree => insertSimpleValue("CloseTree")
            case PAP.DeletePost(clearFlags) =>
              if (clearFlags) insertSimpleValue("DelPostClearFlags")
              else insertSimpleValue("DelPost")
            case PAP.DeleteTree => insertSimpleValue("DelTree")
            case PAP.Delete(_) => unimplemented // there's no DW1_PAGE_ACTIONS.TYPE?
            case PAP.HidePostClearFlags => insertSimpleValue("HidePostClearFlags")
            case PAP.ClearFlags => insertSimpleValue("ClearFlags")
            case PAP.RejectEdits(deleteEdits) =>
              if (deleteEdits) {
                // Would need to actually delete the edits too, not implemented.
                ??? // insertSimpleValue("RejectDeleteEdits")
              }
              else {
                insertSimpleValue("RejectKeepEdits")
              }
          }
    }

    actionsWithIds
  }


  /** Looks at each RawPostAction, and find out which tables and columns to update,
    * because of that action. For example, an action.payload = ClearFlags results in
    * DW1_PAGE_ACTIONS.DELETED_AT/BY_ID being set for all flags for action.postId.
    */
  private def updateAffectedThings(pageId: PageId, actions: Seq[RawPostAction[_]])
        (implicit connection: js.Connection) {

    def setColumn(table: String, pageId: PageId, postId: PostId,
            changedAtColumn: String, date: ju.Date,
            changedByColumn: String, userId: UserId)
            (implicit connection: js.Connection) {
      val siteIdColumn = if (table == "DW1_POSTS") "SITE_ID" else "TENANT"
      var sql = s"""
        update $table set
          $changedAtColumn = ?,
          $changedByColumn = ?
        where $siteIdColumn = ? and PAGE_ID = ? and POST_ID = ? and $changedAtColumn is null"""
      var values = List[AnyRef](d2ts(date), userId, siteId, pageId, postId.asAnyRef)
      db.update(sql, values)
    }

    // Deleting the original post deletes the whole page.
    def deletePage(date: ju.Date, userId: UserId) {
      UNTESTED
      var sql = s"""
        update DW1_PAGES set
          DELETED_AT = ?,
          DELETED_BY_ID = ?
        where TENANT = ? and PAGE_ID = ? and DELETED_AT is null"""
      var values = Vector[AnyRef](d2ts(date), userId, siteId, pageId)
      db.update(sql, values.toList)
    }

    def clearFlags(date: ju.Date, postId: PostId, userId: UserId) {
      // Minor BUG: race condition. What if a flag is created by another thread right here?
      // It would be deleted, although it hasn't yet been considered by any moderator.
      // COULD specify ids of flags to delete, or specify a date/action-id up to which flags
      // are to be deleted.

      // Mark flags as deleted.
      val sql = s"""
        update DW1_PAGE_ACTIONS set DELETED_AT = ?, DELETED_BY_ID = ?
        where TENANT = ?
          and PAGE_ID = ?
          and POST_ID = ?
          and TYPE like 'Flag%'
          and DELETED_AT is null"""
      val values = List[AnyRef](d2ts(date), userId, siteId, pageId, postId.asAnyRef)
      db.update(sql, values)

      // Set pending flag count to 0.
      val sql2 = s"""
        update DW1_POSTS set
          NUM_HANDLED_FLAGS = NUM_HANDLED_FLAGS + NUM_PENDING_FLAGS,
          NUM_PENDING_FLAGS = 0
        where SITE_ID = ?
          and PAGE_ID = ?
          and POST_ID = ?"""
      val values2 = List[AnyRef](siteId, pageId, postId.asAnyRef)
      db.update(sql2, values2)
    }

    for (action <- actions) action.payload match {
      case deletePost: PAP.DeletePost =>
        if (deletePost.clearFlags) {
          clearFlags(action.creationDati, action.postId, action.userId)
        }
        setColumn("DW1_POSTS", pageId, action.postId,
          "POST_DELETED_AT", action.creationDati,
          "POST_DELETED_BY_ID", action.userId)(connection)
        if (action.postId == PageParts.BodyId) {
          deletePage(action.creationDati, action.userId)
        }
      case PAP.DeleteTree =>
        UNTESTED
        setColumn("DW1_POSTS", pageId, action.postId,
          "TREE_DELETED_AT", action.creationDati,
          "TREE_DELETED_BY_ID", action.userId)(connection)
        if (action.postId == PageParts.BodyId) {
          deletePage(action.creationDati, action.userId)
        }
        // We need to update all posts in the tree starting at action.postId. Load the
        // whole page, to find action.postId's successors.
        val pageParts = loadPageParts(pageId) getOrElse { return }
        val successors = pageParts.successorsTo(action.postId)
        for (post <- successors) {
          setColumn("DW1_POSTS", pageId, post.id,
            "POST_DELETED_AT", action.creationDati,
            "POST_DELETED_BY_ID", action.userId)(connection)
        }
      case PAP.HidePostClearFlags =>
        UNTESTED
        clearFlags(action.creationDati, action.postId, action.userId)
        setColumn("DW1_POSTS", pageId, action.id,
          "POST_HIDDEN_AT", action.creationDati,
          "POST_HIDDEN_BY_ID", action.userId)(connection)
      case PAP.ClearFlags =>
        clearFlags(action.creationDati, action.postId, action.userId)
      case _ =>
        // ignore, for now
    }
  }


  def deleteVote(userIdData: UserIdData, pageId: PageId, postId: PostId,
        voteType: PostActionPayload.Vote) {
    db.transaction { connection =>

      def deleteVoteByUserId(): Int = {
        val (guestOrRoleSql, guestOrRoleId) =
          (userIdData.anyGuestId, userIdData.anyRoleId) match {
            case (Some(guestId), None) => ("GUEST_ID = ?", guestId)
            case (None, Some(roleId)) => ("ROLE_ID = ?", roleId)
            case _ => assErr("DwE72RI05")
          }
        val sql = s"""
          delete from DW1_PAGE_ACTIONS
          where TENANT = ?
            and $guestOrRoleSql
            and PAGE_ID = ?
            and POST_ID = ?
            and TYPE = ?
          """
        val values = List[AnyRef](siteId, guestOrRoleId, pageId,
          postId.asInstanceOf[Integer], toActionTypeStr(voteType))
        db.update(sql, values)(connection)
      }

      def deleteVoteByCookie(): Int = {
        val sql = s"""
          delete from DW1_PAGE_ACTIONS
          where TENANT = ?
            and GUEST_ID is null
            and ROLE_ID is null
            and BROWSER_ID_COOKIE = ?
            and PAGE_ID = ?
            and POST_ID = ?
            and TYPE = ?
          """
        val values = List[AnyRef](siteId, userIdData.browserIdCookie.getOrDie("DwE75FE6"),
          pageId, postId.asInstanceOf[Integer], toActionTypeStr(voteType))
        db.update(sql, values)(connection)
      }

      var numRowsDeleted = 0
      if ((userIdData.anyGuestId.isDefined && userIdData.userId != UnknownUser.Id) ||
            userIdData.anyRoleId.isDefined) {
        numRowsDeleted = deleteVoteByUserId()
      }
      if (numRowsDeleted == 0 && userIdData.browserIdCookie.isDefined) {
        numRowsDeleted = deleteVoteByCookie()
      }
      if (numRowsDeleted > 1) {
        assErr("DwE8GCH0", o"""Too many votes deleted, page `$pageId' post `$postId',
          user: $userIdData, vote type: $voteType""")
      }
    }
  }


  def rememberPostsAreIndexed(indexedVersion: Int, pageAndPostIds: PagePostId*) {
    val pagesAndPostsClause =
      pageAndPostIds.map(_ => "(PAGE_ID = ? and PAID = ?)").mkString(" or ")

    val sql = s"""
      update DW1_PAGE_ACTIONS set INDEX_VERSION = ?
      where TENANT = ?
        and ($pagesAndPostsClause)
        and TYPE = 'Post'
      """

    val values = indexedVersion :: siteId ::
      pageAndPostIds.toList.flatMap(x => List(x.pageId, x.postId))

    val numPostsUpdated = db.updateAtnms(sql, values.asInstanceOf[List[AnyRef]])
    assert(numPostsUpdated <= pageAndPostIds.length)
  }


  private def insertUpdatePost(post: Post)(implicit conn: js.Connection) {
    // Ignore race conditions. The next time `post` is insert-overwritten,
    // any inconsistency will be fixed?

    // Note that the update and the insert specifies parameters in exactly
    // the same order.

    val updateSql = """
      update DW1_POSTS set
        PARENT_POST_ID = ?,
        MULTIREPLY = ?,
        WHEERE = ?,
        CREATED_AT = ?,
        LAST_ACTED_UPON_AT = ?,
        LAST_REVIEWED_AT = ?,
        LAST_AUTHLY_REVIEWED_AT = ?,
        LAST_APPROVED_AT = ?,
        LAST_APPROVAL_TYPE = ?,
        LAST_PERMANENTLY_APPROVED_AT = ?,
        LAST_MANUALLY_APPROVED_AT = ?,
        LAST_MANUALLY_APPROVED_BY_ID = ?,
        AUTHOR_ID = ?,
        LAST_EDIT_APPLIED_AT = ?,
        LAST_EDIT_REVERTED_AT = ?,
        LAST_EDITOR_ID = ?,

        PINNED_POSITION = ?,
        POST_COLLAPSED_AT = ?,
        TREE_COLLAPSED_AT = ?,
        TREE_CLOSED_AT = ?,
        POST_DELETED_AT = ?,
        POST_DELETED_BY_ID = ?,
        TREE_DELETED_AT = ?,
        TREE_DELETED_BY_ID = ?,
        POST_HIDDEN_AT = ?,
        POST_HIDDEN_BY_ID = ?,

        NUM_EDIT_SUGGESTIONS = ?,
        NUM_EDITS_APPLD_UNREVIEWED = ?,
        NUM_EDITS_APPLD_PREL_APPROVED = ?,
        NUM_EDITS_TO_REVIEW = ?,
        NUM_DISTINCT_EDITORS = ?,

        NUM_COLLAPSE_POST_VOTES_PRO = ?,
        NUM_COLLAPSE_POST_VOTES_CON = ?,
        NUM_UNCOLLAPSE_POST_VOTES_PRO = ?,
        NUM_UNCOLLAPSE_POST_VOTES_CON = ?,
        NUM_COLLAPSE_TREE_VOTES_PRO = ?,
        NUM_COLLAPSE_TREE_VOTES_CON = ?,
        NUM_UNCOLLAPSE_TREE_VOTES_PRO = ?,
        NUM_UNCOLLAPSE_TREE_VOTES_CON = ?,
        NUM_COLLAPSES_TO_REVIEW = ?,
        NUM_UNCOLLAPSES_TO_REVIEW = ?,

        NUM_DELETE_POST_VOTES_PRO = ?,
        NUM_DELETE_POST_VOTES_CON = ?,
        NUM_UNDELETE_POST_VOTES_PRO = ?,
        NUM_UNDELETE_POST_VOTES_CON = ?,
        NUM_DELETE_TREE_VOTES_PRO = ?,
        NUM_DELETE_TREE_VOTES_CON = ?,
        NUM_UNDELETE_TREE_VOTES_PRO = ?,
        NUM_UNDELETE_TREE_VOTES_CON = ?,
        NUM_DELETES_TO_REVIEW = ?,
        NUM_UNDELETES_TO_REVIEW = ?,

        NUM_PENDING_FLAGS = ?,
        NUM_HANDLED_FLAGS = ?,
        FLAGS = ?,
        RATINGS = ?,
        APPROVED_TEXT = ?,
        UNAPPROVED_TEXT_DIFF = ?
      where SITE_ID = ? and PAGE_ID = ? and POST_ID = ?"""

    val insertSql = """
      insert into DW1_POSTS (
        PARENT_POST_ID,
        MULTIREPLY,
        WHEERE,
        CREATED_AT,
        LAST_ACTED_UPON_AT,
        LAST_REVIEWED_AT,
        LAST_AUTHLY_REVIEWED_AT,
        LAST_APPROVED_AT,
        LAST_APPROVAL_TYPE,
        LAST_PERMANENTLY_APPROVED_AT,
        LAST_MANUALLY_APPROVED_AT,
        LAST_MANUALLY_APPROVED_BY_ID,
        AUTHOR_ID,
        LAST_EDIT_APPLIED_AT,
        LAST_EDIT_REVERTED_AT,
        LAST_EDITOR_ID,

        PINNED_POSITION,
        POST_COLLAPSED_AT,
        TREE_COLLAPSED_AT,
        TREE_CLOSED_AT,
        POST_DELETED_AT,
        POST_DELETED_BY_ID,
        TREE_DELETED_AT,
        TREE_DELETED_BY_ID,
        POST_HIDDEN_AT,
        POST_HIDDEN_BY_ID,

        NUM_EDIT_SUGGESTIONS,
        NUM_EDITS_APPLD_UNREVIEWED,
        NUM_EDITS_APPLD_PREL_APPROVED,
        NUM_EDITS_TO_REVIEW,
        NUM_DISTINCT_EDITORS,

        NUM_COLLAPSE_POST_VOTES_PRO,
        NUM_COLLAPSE_POST_VOTES_CON,
        NUM_UNCOLLAPSE_POST_VOTES_PRO,
        NUM_UNCOLLAPSE_POST_VOTES_CON,
        NUM_COLLAPSE_TREE_VOTES_PRO,
        NUM_COLLAPSE_TREE_VOTES_CON,
        NUM_UNCOLLAPSE_TREE_VOTES_PRO,
        NUM_UNCOLLAPSE_TREE_VOTES_CON,
        NUM_COLLAPSES_TO_REVIEW,
        NUM_UNCOLLAPSES_TO_REVIEW,

        NUM_DELETE_POST_VOTES_PRO,
        NUM_DELETE_POST_VOTES_CON,
        NUM_UNDELETE_POST_VOTES_PRO,
        NUM_UNDELETE_POST_VOTES_CON,
        NUM_DELETE_TREE_VOTES_PRO,
        NUM_DELETE_TREE_VOTES_CON,
        NUM_UNDELETE_TREE_VOTES_PRO,
        NUM_UNDELETE_TREE_VOTES_CON,
        NUM_DELETES_TO_REVIEW,
        NUM_UNDELETES_TO_REVIEW,

        NUM_PENDING_FLAGS,
        NUM_HANDLED_FLAGS,
        FLAGS,
        RATINGS,
        APPROVED_TEXT,
        UNAPPROVED_TEXT_DIFF,
        SITE_ID, PAGE_ID, POST_ID)
      values
        (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
         ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
         ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
         ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
         ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
         ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

    val collapsed: AnyRef =
      if (post.isTreeCollapsed) "CollapseTree"
      else if (post.isPostCollapsed) "CollapsePost"
      else NullVarchar

    val anyLastEditor = post.editsAppliedDescTime.headOption.map(_.user_!)
    val anyUnapprovedTextDiff = post.unapprovedText map { unapprovedText =>
      makePatch(from = post.approvedText.getOrElse(""), to = unapprovedText)
    }

    val values = List[AnyRef](
      post.parentId.orNullInt,
      toDbMultireply(post.multireplyPostIds),
      post.where.orNullVarchar,
      d2ts(post.creationDati),
      d2ts(post.lastActedUponAt),
      o2ts(post.lastReviewDati), // LAST_REVIEWED_AT
      o2ts(post.lastAuthoritativeReviewDati), // LAST_AUTHLY_REVIEWED_AT
      o2ts(post.lastApprovalDati), // LAST_APPROVED_AT
      _toDbVal(post.lastApprovalType), // LAST_APPROVAL_TYPE
      o2ts(post.lastPermanentApprovalDati), // LAST_PERMANENTLY_APPROVED_AT
      o2ts(post.lastManualApprovalDati), // LAST_MANUALLY_APPROVED_AT
      post.lastManuallyApprovedById.orNullVarchar,
      post.userId,  // AUTHOR_ID
      o2ts(post.lastEditAppliedAt), // LAST_EDIT_APPLIED_AT
      o2ts(post.lastEditRevertedAt), // LAST_EDIT_REVERTED_AT
      anyLastEditor.map(_.id).orNullVarchar, // LAST_EDITOR_ID

      post.pinnedPosition.orNullInt,
      o2ts(post.postCollapsedAt),
      o2ts(post.treeCollapsedAt),
      o2ts(post.treeClosedAt),
      o2ts(post.postDeletedAt),
      post.postDeletedById.orNullVarchar,
      o2ts(post.treeDeletedAt),
      post.treeDeletedById.orNullVarchar,
      o2ts(post.postHiddenAt),
      post.postHiddenById.orNullVarchar,

      post.numPendingEditSuggestions.asInstanceOf[AnyRef],
      post.numEditsAppliedUnreviewed.asInstanceOf[AnyRef],
      post.numEditsAppldPrelApproved.asInstanceOf[AnyRef],
      post.numEditsToReview.asInstanceOf[AnyRef],
      post.numDistinctEditors.asInstanceOf[AnyRef],

      post.numCollapsePostVotesPro.asInstanceOf[AnyRef],
      post.numCollapsePostVotesCon.asInstanceOf[AnyRef],
      post.numUncollapsePostVotesPro.asInstanceOf[AnyRef],
      post.numUncollapsePostVotesCon.asInstanceOf[AnyRef],

      post.numCollapseTreeVotesPro.asInstanceOf[AnyRef],
      post.numCollapseTreeVotesCon.asInstanceOf[AnyRef],
      post.numUncollapseTreeVotesPro.asInstanceOf[AnyRef],
      post.numUncollapseTreeVotesCon.asInstanceOf[AnyRef],

      post.numCollapsesToReview.asInstanceOf[AnyRef],
      post.numUncollapsesToReview.asInstanceOf[AnyRef],

      post.numDeletePostVotesPro.asInstanceOf[AnyRef],
      post.numDeletePostVotesCon.asInstanceOf[AnyRef],
      post.numUndeletePostVotesPro.asInstanceOf[AnyRef],
      post.numUndeletePostVotesCon.asInstanceOf[AnyRef],

      post.numDeleteTreeVotesPro.asInstanceOf[AnyRef],
      post.numDeleteTreeVotesCon.asInstanceOf[AnyRef],
      post.numUndeleteTreeVotesPro.asInstanceOf[AnyRef],
      post.numUndeleteTreeVotesCon.asInstanceOf[AnyRef],

      post.numDeletesToReview.asInstanceOf[AnyRef],
      post.numUndeletesToReview.asInstanceOf[AnyRef],

      post.numPendingFlags.asInstanceOf[AnyRef],
      post.numHandledFlags.asInstanceOf[AnyRef],
      NullVarchar, // post.flagsDescTime
      NullVarchar, // ratings text
      post.approvedText.orNullVarchar,
      anyUnapprovedTextDiff.orNullVarchar,
      siteId, post.page.pageId, post.id.asAnyRef)

    val numRowsChanged = db.update(updateSql, values)
    if (numRowsChanged == 0) {
      val numInserted = db.update(insertSql, values)
      assErrIf(numInserted != 1, "DwE8W3Y9")
    }
    else {
      assErrIf(numRowsChanged != 1, "DwE4IF01")
    }
  }


  // Note: The `where` and `order by` clauses below must exactly match
  // the indexes defined in the database (or there'll be full table scans I suppose).
  // Therefore: Only edit the where tests below, by copy-pasting from
  // debiki-postgre.sql (the indexes below `create table DW1_POSTS`).

  /** Loads non-deleted posts with pending flags
    */
  private def loadPostStatesPendingFlags(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : Vector[(PageId, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = """
        POST_DELETED_AT is null and
        TREE_DELETED_AT is null and
        NUM_PENDING_FLAGS > 0
      """,
      orderBy = Some("SITE_ID, NUM_PENDING_FLAGS desc"))


  /** Loads non-deleted posts with edits to review, or that are new and pending
    * approval.
    */
  private def loadPostStatesPendingApproval(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : Vector[(PageId, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = s"""
        POST_DELETED_AT is null and
        TREE_DELETED_AT is null and
        NUM_PENDING_FLAGS = 0 and (
          (LAST_APPROVAL_TYPE is null or LAST_APPROVAL_TYPE = 'P') or
          NUM_EDITS_TO_REVIEW > 0 or
          NUM_COLLAPSES_TO_REVIEW > 0 or
          NUM_UNCOLLAPSES_TO_REVIEW > 0 or
          NUM_DELETES_TO_REVIEW > 0 or
          NUM_UNDELETES_TO_REVIEW > 0)
        """,
      orderBy = Some("SITE_ID, LAST_ACTED_UPON_AT desc"))


  /** Loads non-deleted posts with edit suggestions or other suggestions.
    */
  private def loadPostStatesWithSuggestions(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : Vector[(PageId, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = s"""
        POST_DELETED_AT is null and
        TREE_DELETED_AT is null and
        NUM_PENDING_FLAGS = 0 and
        LAST_APPROVAL_TYPE in ('W', 'A', 'M') and
        NUM_EDITS_TO_REVIEW = 0 and
        NUM_COLLAPSES_TO_REVIEW = 0 and
        NUM_UNCOLLAPSES_TO_REVIEW = 0 and
        NUM_DELETES_TO_REVIEW = 0 and
        NUM_UNDELETES_TO_REVIEW = 0 and (
          NUM_EDIT_SUGGESTIONS > 0 or
          (NUM_COLLAPSE_POST_VOTES_PRO > 0   and POST_COLLAPSED_AT is null) or
          (NUM_UNCOLLAPSE_POST_VOTES_PRO > 0 and POST_COLLAPSED_AT is not null) or
          (NUM_COLLAPSE_TREE_VOTES_PRO > 0   and TREE_COLLAPSED_AT is null) or
          (NUM_UNCOLLAPSE_TREE_VOTES_PRO > 0 and TREE_COLLAPSED_AT is not null) or
          (NUM_DELETE_POST_VOTES_PRO > 0     and POST_DELETED_AT is null) or
          (NUM_UNDELETE_POST_VOTES_PRO > 0   and POST_DELETED_AT is not null) or
          (NUM_DELETE_TREE_VOTES_PRO > 0     and TREE_DELETED_AT is null) or
          (NUM_UNDELETE_TREE_VOTES_PRO > 0   and TREE_DELETED_AT is not null))
        """,
      orderBy = Some("SITE_ID, LAST_ACTED_UPON_AT desc"))


  /** Loads uninteresting posts, namely posts that have already been reviewed or deleted.
    */
  private def loadPostStatesHandled(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : Vector[(PageId, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = s"""(
        POST_DELETED_AT is not null or
        TREE_DELETED_AT is not null
      ) or (
        NUM_PENDING_FLAGS = 0 and
        LAST_APPROVAL_TYPE in ('W', 'A', 'M') and
        NUM_EDITS_TO_REVIEW = 0 and
        NUM_COLLAPSES_TO_REVIEW = 0 and
        NUM_UNCOLLAPSES_TO_REVIEW = 0 and
        NUM_DELETES_TO_REVIEW = 0 and
        NUM_UNDELETES_TO_REVIEW = 0 and
        NUM_EDIT_SUGGESTIONS = 0 and not (
          NUM_EDIT_SUGGESTIONS > 0 or
          (NUM_COLLAPSE_POST_VOTES_PRO > 0   and POST_COLLAPSED_AT is null) or
          (NUM_UNCOLLAPSE_POST_VOTES_PRO > 0 and POST_COLLAPSED_AT is not null) or
          (NUM_COLLAPSE_TREE_VOTES_PRO > 0   and TREE_COLLAPSED_AT is null) or
          (NUM_UNCOLLAPSE_TREE_VOTES_PRO > 0 and TREE_COLLAPSED_AT is not null) or
          (NUM_DELETE_POST_VOTES_PRO > 0     and POST_DELETED_AT is null) or
          (NUM_UNDELETE_POST_VOTES_PRO > 0   and POST_DELETED_AT is not null) or
          (NUM_DELETE_TREE_VOTES_PRO > 0     and TREE_DELETED_AT is null) or
          (NUM_UNDELETE_TREE_VOTES_PRO > 0   and TREE_DELETED_AT is not null))
      )""",
      orderBy = Some("SITE_ID, LAST_ACTED_UPON_AT desc"))


  private def loadPostStatesImpl(
      limit: Int, offset: Int, whereTests: String, orderBy: Option[String] = None)(
      implicit connection: js.Connection): Vector[(PageId, PostState)] = {

    val orderByClause = orderBy.map("order by " + _) getOrElse ""
    val sql = s"""
      select * from DW1_POSTS
      where SITE_ID = ? and ($whereTests)
      $orderByClause limit $limit offset $offset
      """

    val values = List(siteId)
    var result = Vector[(PageId, PostState)]()

    db.query(sql, values, rs => {
      while (rs.next) {
        val pageId = rs.getString("PAGE_ID")
        result :+= (pageId, readPostState(rs))
      }
    })

    result
  }


  def loadPostStatesById(pagePostIds: Seq[PagePostId])(implicit connection: js.Connection)
        : Map[PagePostId, PostState] = {
    if (pagePostIds.isEmpty)
      return Map.empty

    var values = mutable.ArrayBuffer[AnyRef](siteId)

    val pagePostIdsClause = new StringBuilder()
    for (pagePostId <- pagePostIds) {
      values += pagePostId.pageId
      values += pagePostId.postId.asAnyRef
      if (pagePostIdsClause.nonEmpty) pagePostIdsClause.append(" or ")
      pagePostIdsClause.append("(PAGE_ID = ? and POST_ID = ?)")
    }

    val sql = s"""
      select * from DW1_POSTS
      where SITE_ID = ? and (${pagePostIdsClause.toString})"""

    var result = Map[PagePostId, PostState]()
    db.query(sql, values.toList, rs => {
      while (rs.next) {
        val pageId = rs.getString("PAGE_ID")
        val postId = rs.getInt("POST_ID")
        result += PagePostId(pageId, postId = postId) -> readPostState(rs)
      }
    })
    result
  }


  private def readPostState(rs: js.ResultSet): PostState = {

    val anyApprovedText = Option(rs.getString("APPROVED_TEXT"))
    val anyUnapprovedTextDiff = Option(rs.getString("UNAPPROVED_TEXT_DIFF"))
    val anyUnapprovedText = anyUnapprovedTextDiff map { patchText =>
      applyPatch(patchText, to = anyApprovedText getOrElse "")
    }

    val userIdData = UserIdData(
      userId = rs.getString("AUTHOR_ID"),
      ip = "0.0.0.0", // for now
      browserIdCookie = None, // for now
      browserFingerprint = 0) // for now

    val rawPostAction = RawPostAction.forNewPost(
      id = rs.getInt("POST_ID"),
      creationDati = ts2d(rs.getTimestamp("CREATED_AT")),
      userIdData = userIdData,
      parentPostId = getOptionalIntNoneNot0(rs, "PARENT_POST_ID"),
      multireplyPostIds = fromDbMultireply(rs.getString("MULTIREPLY")),
      text = anyUnapprovedText getOrElse anyApprovedText.get,
      approval = _toAutoApproval(rs.getString("LAST_APPROVAL_TYPE")),
      where = Option(rs.getString("WHEERE")))

    new PostState(
      rawPostAction,
      lastActedUponAt = ts2d(rs.getTimestamp("LAST_ACTED_UPON_AT")),
      lastReviewDati = ts2o(rs.getTimestamp("LAST_REVIEWED_AT")),
      lastAuthoritativeReviewDati = ts2o(rs.getTimestamp("LAST_AUTHLY_REVIEWED_AT")),
      lastApprovalDati = ts2o(rs.getTimestamp("LAST_APPROVED_AT")),
      lastApprovedText = anyApprovedText,
      lastPermanentApprovalDati = ts2o(rs.getTimestamp("LAST_PERMANENTLY_APPROVED_AT")),
      lastManualApprovalDati = ts2o(rs.getTimestamp("LAST_MANUALLY_APPROVED_AT")),
      lastManuallyApprovedById = Option(rs.getString("LAST_MANUALLY_APPROVED_BY_ID")),
      lastEditAppliedAt = ts2o(rs.getTimestamp("LAST_EDIT_APPLIED_AT")),
      lastEditRevertedAt = ts2o(rs.getTimestamp("LAST_EDIT_REVERTED_AT")),
      lastEditorId = Option(rs.getString("LAST_EDITOR_ID")),
      pinnedPosition = Option(rs.getInt("PINNED_POSITION")),
      postCollapsedAt = ts2o(rs.getTimestamp("POST_COLLAPSED_AT")),
      treeCollapsedAt = ts2o(rs.getTimestamp("TREE_COLLAPSED_AT")),
      treeClosedAt = ts2o(rs.getTimestamp("TREE_CLOSED_AT")),
      postDeletedAt = ts2o(rs.getTimestamp("POST_DELETED_AT")),
      postDeletedById = Option(rs.getString("POST_DELETED_BY_ID")),
      treeDeletedAt = ts2o(rs.getTimestamp("TREE_DELETED_AT")),
      treeDeletedById = Option(rs.getString("TREE_DELETED_BY_ID")),
      postHiddenAt = ts2o(rs.getTimestamp("POST_HIDDEN_AT")),
      postHiddenById = Option(rs.getString("POST_HIDDEN_BY_ID")),
      numEditSuggestions = rs.getInt("NUM_EDIT_SUGGESTIONS"),
      numEditsAppliedUnreviewed = rs.getInt("NUM_EDITS_APPLD_UNREVIEWED"),
      numEditsAppldPrelApproved = rs.getInt("NUM_EDITS_APPLD_PREL_APPROVED"),
      numEditsToReview = rs.getInt("NUM_EDITS_TO_REVIEW"),
      numDistinctEditors = rs.getInt("NUM_DISTINCT_EDITORS"),
      numCollapsePostVotes = PostVoteState(
        pro = rs.getInt("NUM_COLLAPSE_POST_VOTES_PRO"),
        con = rs.getInt("NUM_COLLAPSE_POST_VOTES_CON"),
        undoPro = rs.getInt("NUM_UNCOLLAPSE_POST_VOTES_PRO"),
        undoCon = rs.getInt("NUM_UNCOLLAPSE_POST_VOTES_CON")),
      numCollapseTreeVotes = PostVoteState(
        pro = rs.getInt("NUM_COLLAPSE_TREE_VOTES_PRO"),
        con = rs.getInt("NUM_COLLAPSE_TREE_VOTES_CON"),
        undoPro = rs.getInt("NUM_UNCOLLAPSE_TREE_VOTES_PRO"),
        undoCon = rs.getInt("NUM_UNCOLLAPSE_TREE_VOTES_CON")),
      numCollapsesToReview = rs.getInt("NUM_COLLAPSES_TO_REVIEW"),
      numUncollapsesToReview = rs.getInt("NUM_UNCOLLAPSES_TO_REVIEW"),
      numDeletePostVotes = PostVoteState(
        pro = rs.getInt("NUM_DELETE_POST_VOTES_PRO"),
        con = rs.getInt("NUM_DELETE_POST_VOTES_CON"),
        undoPro = rs.getInt("NUM_UNDELETE_POST_VOTES_PRO"),
        undoCon = rs.getInt("NUM_UNDELETE_POST_VOTES_CON")),
      numDeleteTreeVotes = PostVoteState(
        pro = rs.getInt("NUM_DELETE_TREE_VOTES_PRO"),
        con = rs.getInt("NUM_DELETE_TREE_VOTES_CON"),
        undoPro = rs.getInt("NUM_UNDELETE_TREE_VOTES_PRO"),
        undoCon = rs.getInt("NUM_UNDELETE_TREE_VOTES_CON")),
      numDeletesToReview = rs.getInt("NUM_DELETES_TO_REVIEW"),
      numUndeletesToReview = rs.getInt("NUM_UNDELETES_TO_REVIEW"),
      numPendingFlags = rs.getInt("NUM_PENDING_FLAGS"),
      numHandledFlags = rs.getInt("NUM_HANDLED_FLAGS"))
  }


}



