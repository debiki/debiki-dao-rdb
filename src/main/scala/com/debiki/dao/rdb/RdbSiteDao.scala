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
  val siteId: SiteId,
  val daoFactory: RdbDaoFactory)
  extends SiteDbDao
  with PageSiteDaoMixin
  with FullTextSearchSiteDaoMixin
  with UserSiteDaoMixin
  with UserActionInfoSiteDaoMixin
  with LoginSiteDaoMixin
  with PostsReadStatsSiteDaoMixin
  with NotificationsSiteDaoMixin
  with SettingsSiteDaoMixin
  with SiteTransaction {


  val MaxWebsitesPerIp = 6

  val LocalhostAddress = "127.0.0.1"

  def db = systemDaoSpi.db

  @deprecated("use systemDao instead", "now")
  def systemDaoSpi = daoFactory.systemDbDao // why this weird name?

  lazy val systemDao: RdbSystemDao = {
    val transaction = new RdbSystemDao(daoFactory)
    transaction.setTheOneAndOnlyConnection(theOneAndOnlyConnection)
    transaction
  }

  def fullTextSearchIndexer = daoFactory.fullTextSearchIndexer

  def commonMarkRenderer: CommonMarkRenderer = daoFactory.commonMarkRenderer

  lazy val currentTime: ju.Date = systemDao.currentTime



  /** If set, should be the only connection that this dao uses. Some old code doesn't
    * create it though, then different connections are used instead :-(
    * I'll rename it to 'connection', when all that old code is gone and there's only
    * one connection always.
    */
  def anyOneAndOnlyConnection =
    _theOneAndOnlyConnection

  // COULD move to new superclass?
  def theOneAndOnlyConnection = {
    if (transactionEnded)
      throw new IllegalStateException("Transaction has ended [DwE4GKP53]")
    _theOneAndOnlyConnection getOrElse {
      die("DwE83KV21")
    }
  }

  private var _theOneAndOnlyConnection: Option[js.Connection] = None

  // COULD move to new superclass?
  private var transactionEnded = false

  // COULD move to new superclass?
  def createTheOneAndOnlyConnection(readOnly: Boolean) {
    require(_theOneAndOnlyConnection.isEmpty)
    _theOneAndOnlyConnection = Some(db.getConnection(readOnly))
  }

  // COULD move to new superclass?
  def setTheOneAndOnlyConnection(connection: js.Connection) {
    require(_theOneAndOnlyConnection.isEmpty)
    _theOneAndOnlyConnection = Some(connection)
  }

  // COULD move to new superclass?
  def commit() {
    if (_theOneAndOnlyConnection.isEmpty)
      throw new IllegalStateException("No permanent connection created [DwE5KF2]")
    theOneAndOnlyConnection.commit()
    db.closeConnection(theOneAndOnlyConnection)
    transactionEnded = true
  }


  // COULD move to new superclass?
  def rollback() {
    if (_theOneAndOnlyConnection.isEmpty)
      throw new IllegalStateException("No permanent connection created [DwE2K57]")
    theOneAndOnlyConnection.rollback()
    db.closeConnection(theOneAndOnlyConnection)
    transactionEnded = true
  }


  def transactionCheckQuota[T](f: (js.Connection) => T): T = {
    anyOneAndOnlyConnection foreach { connection =>
      // In this case I've moved the over quota check to com.debiki.core.DbDao2.
      return f(connection)
    }
    systemDaoSpi.db.transaction { connection =>
      val result = f(connection)
      val resourceUsage = loadResourceUsage(connection)
      resourceUsage.quotaLimitMegabytes foreach { limit =>
        val quotaExceededBytes = resourceUsage.estimatedBytesUsed - limit * 1000L * 1000L
        if (quotaExceededBytes > 0)
          throw OverQuotaException(siteId, resourceUsage)
      }
      result
    }
  }


  def transactionAllowOverQuota[T](f: (js.Connection) => T): T = {
    anyOneAndOnlyConnection foreach { connection =>
      return f(connection)
    }
    systemDaoSpi.db.transaction(f)
  }


  // COULD move to new superclass?
  def runQuery[R](query: String, values: List[AnyRef], resultSetHandler: js.ResultSet => R): R = {
    db.query(query, values, resultSetHandler)(theOneAndOnlyConnection)
  }


  // COULD move to new superclass?
  def runUpdate(statement: String, values: List[AnyRef] = Nil): Int = {
    db.update(statement, values)(theOneAndOnlyConnection)
  }


  // COULD move to new superclass?
  def queryAtnms[R](query: String, values: List[AnyRef], resultSetHandler: js.ResultSet => R): R = {
    anyOneAndOnlyConnection foreach { connection =>
      return db.query(query, values, resultSetHandler)(connection)
    }
    db.queryAtnms(query, values, resultSetHandler)
  }


  def loadResourceUsage() = loadResourceUsage(theOneAndOnlyConnection)


  def loadResourceUsage(connection: js.Connection): ResourceUse = {
    val sql = """
      select
        QUOTA_LIMIT_MBS,
        NUM_GUESTS,
        NUM_IDENTITIES,
        NUM_ROLES,
        NUM_ROLE_SETTINGS,
        NUM_PAGES,
        NUM_POSTS,
        NUM_POST_TEXT_BYTES,
        NUM_POSTS_READ,
        NUM_ACTIONS,
        NUM_ACTION_TEXT_BYTES,
        NUM_NOTFS,
        NUM_EMAILS_SENT
      from DW1_TENANTS where ID = ?"""
    db.query(sql, List(siteId), rs => {
      rs.next()
      dieUnless(rs.isLast(), "DwE59FKQ2")
      ResourceUse(
        quotaLimitMegabytes = getResultSetIntOption(rs, "QUOTA_LIMIT_MBS"),
        numGuests = rs.getInt("NUM_GUESTS"),
        numIdentities = rs.getInt("NUM_IDENTITIES"),
        numRoles = rs.getInt("NUM_ROLES"),
        numRoleSettings = rs.getInt("NUM_ROLE_SETTINGS"),
        numPages = rs.getInt("NUM_PAGES"),
        numPosts = rs.getInt("NUM_POSTS"),
        numPostTextBytes = rs.getLong("NUM_POST_TEXT_BYTES"),
        numPostsRead = rs.getInt("NUM_POSTS_READ"),
        numActions = rs.getInt("NUM_ACTIONS"),
        numActionTextBytes = rs.getLong("NUM_ACTION_TEXT_BYTES"),
        numNotfs = rs.getInt("NUM_NOTFS"),
        numEmailsSent = rs.getInt("NUM_EMAILS_SENT"))
    })(connection)
  }


  /** Some SQL operations might cause harmless errors, then we try again.
    *
    * One harmless error: Generating random ids and one happens to clash with
    * an existing id. Simply try again with another id.
    * Another harmless error (except w.r.t. performance) is certain deadlocks.
    * See the implementation of savePageActions() for details -- no, now it's been
    * deleted. Instead see below, just below, at [BKFF321]. And also:
    * see: http://www.postgresql.org/message-id/1078934613.17553.66.camel@coppola.ecircle.de
    * and: < http://postgresql.1045698.n5.nabble.com/
    *         Foreign-Keys-and-Deadlocks-tp4962572p4967236.html >
    *
    *  [BKFF321]: Old comment from former savePageActions:
    * """Try many times, because harmless deadlocks might abort the first attempt.
    * Example: Editing a row with a foreign key to table R result in a shared lock
    * on the referenced row in table R — so if two sessions A and B insert rows for
    * the same page into DW1_PAGE_ACTIONS and then update DW1_PAGES aftewards,
    * the update statement from session A blocks on the shared lock that
    * B holds on the DW1_PAGES row, and then session B blocks on the exclusive
    * lock on the DW1_PAGES row that A's update statement is trying to grab.
    * An E2E test that fails without `tryManyTimes` here is `EditActivitySpec`."""
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
    transactionCheckQuota { connection =>
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


  def loadAllPageMetas(): immutable.Seq[PageMeta] =
    loadPageMetaImpl(pageIds = Nil, all = true)(theOneAndOnlyConnection).values.to[immutable.Seq]


  def loadPageMetas(pageIds: Seq[PageId]): immutable.Seq[PageMeta] =
    loadPageMetaImpl(pageIds, all = false)(theOneAndOnlyConnection).values.to[immutable.Seq]

  def loadPageMetasAsMap(pageIds: Iterable[PageId]): Map[PageId, PageMeta] =
    loadPageMetaImpl(pageIds.toSeq, all = false)(theOneAndOnlyConnection)

  def loadPageMeta(pageId: PageId): Option[PageMeta] = loadPageMeta(pageId, None)


  def loadPageMeta(pageId: PageId, anySiteId: Option[SiteId]): Option[PageMeta] = {
    loadPageMetasAsMap(pageId::Nil, anySiteId) get pageId
  }


  def loadPageMetasAsMap(pageIds: Seq[PageId], anySiteId: Option[SiteId] = None)
        : Map[PageId, PageMeta] = {
    if (pageIds.isEmpty) return Map.empty
    db.withConnection { loadPageMetaImpl(pageIds, all = false, anySiteId)(_) }
  }


  def loadPageMetaImpl(pageIds: Seq[PageId], all: Boolean = false,
        anySiteId: Option[SiteId] = None)(connection: js.Connection): Map[PageId, PageMeta] = {
    if (!all && pageIds.isEmpty)
      return Map.empty

    val values: List[AnyRef] =
      if (all) List(anySiteId.getOrElse(siteId))
      else anySiteId.getOrElse(siteId) :: pageIds.toList
    var sql = s"""
        select g.GUID, ${_PageMetaSelectListItems}
        from DW1_PAGES g
        where g.TENANT = ?
        """
    if (!all) {
      sql += s" and g.GUID in (${ makeInListFor(pageIds) })"
    }
    var metaByPageId = Map[PageId, PageMeta]()
    db.query(sql, values, rs => {
      while (rs.next) {
        val pageId = rs.getString("GUID")
        val meta = _PageMeta(rs, pageId = pageId)
        metaByPageId += pageId -> meta
      }
    })(anyOneAndOnlyConnection getOrElse connection)
    metaByPageId
  }


  def updatePageMeta(meta: PageMeta, old: PageMeta) {
    transactionCheckQuota {
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


  def loadAncestorPostIdsParentFirst(pageId: PageId): immutable.Seq[PageId] =
    loadAncestorIdsParentFirstImpl(pageId)(theOneAndOnlyConnection)


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
    transactionCheckQuota { implicit connection =>
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
    transactionCheckQuota { implicit connection =>
      moveRenamePageImpl(pageId, newFolder = newFolder, showId = showId,
         newSlug = newSlug)
    }
  }


  def loadPagePartsOld(pageId: PageId): Option[PageParts] = loadPageParts(pageId)


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
    queryAtnms("""
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


  def loadTenant(): Tenant = {
    systemDaoSpi.loadTenants(List(siteId)).head
    // Should tax quotaConsumer with 2 db IO requests: tenant + tenant hosts.
  }


  def loadSiteStatus(): SiteStatus = {
    val sql = """
      select
        exists(select 1 from DW1_USERS where SUPERADMIN = 'T' and TENANT = ?) as admin_exists,
        exists(select 1 from DW1_PAGES where TENANT = ?) as content_exists,
        (select CREATOR_EMAIL_ADDRESS from DW1_TENANTS where ID = ?) as admin_email,
        (select EMBEDDING_SITE_URL from DW1_TENANTS where ID = ?) as embedding_site_url"""
    db.queryAtnms(sql, List(siteId, siteId, siteId, siteId), rs => {
      rs.next()
      val adminExists = rs.getBoolean("admin_exists")
      val contentExists = rs.getBoolean("content_exists")
      val adminEmail = rs.getString("admin_email")
      val anyEmbeddingSiteUrl = Option(rs.getString("embedding_site_url"))

      if (!adminExists)
        return SiteStatus.OwnerCreationPending(adminEmail)

      if (!contentExists && anyEmbeddingSiteUrl.isDefined)
        return SiteStatus.IsEmbeddedSite

      if (!contentExists)
        return SiteStatus.ContentCreationPending

      return SiteStatus.IsSimpleSite
    })
  }


  def createSite(name: String, hostname: String, embeddingSiteUrl: Option[String],
        creatorIp: String, creatorEmailAddress: String, quotaLimitMegabytes: Option[Int]): Tenant = {
    try {
      transactionCheckQuota { implicit connection =>
        // Unless apparently testing from localhost, don't allow someone to create
        // very many sites.
        if (creatorIp != LocalhostAddress) {
          val websiteCount = countWebsites(
            createdFromIp = creatorIp, creatorEmailAddress = creatorEmailAddress)
          if (websiteCount >= MaxWebsitesPerIp)
            throw TooManySitesCreatedException(creatorIp)
        }

        val newTenantNoId = Tenant(id = "?", name = name, creatorIp = creatorIp,
          creatorEmailAddress = creatorEmailAddress, embeddingSiteUrl = embeddingSiteUrl,
          hosts = Nil)
        val newTenant = insertSite(newTenantNoId, quotaLimitMegabytes)
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


  def updateSite(changedSite: Tenant) {
    val currentSite = loadTenant()
    require(changedSite.id == this.siteId,
      "Cannot change site id [DwE32KB80]")
    require(changedSite.creatorEmailAddress == currentSite.creatorEmailAddress,
      "Cannot change site creator email address [DwE32KB80]")
    require(changedSite.creatorIp == currentSite.creatorIp,
      "Cannot change site creator IP [DwE3BK777]")

    val sql = """
      update DW1_TENANTS
      set NAME = ?, EMBEDDING_SITE_URL = ?
      where ID = ?"""
    val values =
      List(changedSite.name, changedSite.embeddingSiteUrl.orNullVarchar, siteId)

    try {
      transactionCheckQuota { implicit connection =>
        db.update(sql, values)
      }
    }
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex)) throw ex
        throw new SiteAlreadyExistsException(changedSite.name)
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


  private def insertSite(tenantNoId: Tenant, quotaLimitMegabytes: Option[Int])
        (implicit connection: js.Connection): Tenant = {
    assErrIf(tenantNoId.id != "?", "DwE91KB2")
    val tenant = tenantNoId.copy(
      id = db.nextSeqNo("DW1_TENANTS_ID").toString)
    db.update("""
        insert into DW1_TENANTS (
          ID, NAME, EMBEDDING_SITE_URL, CREATOR_IP, CREATOR_EMAIL_ADDRESS, QUOTA_LIMIT_MBS)
        values (?, ?, ?, ?, ?, ?)""",
      List[AnyRef](tenant.id, tenant.name,
        tenant.embeddingSiteUrl.orNullVarchar, tenant.creatorIp,
        tenant.creatorEmailAddress, quotaLimitMegabytes.orNullInt))
    tenant
  }


  def addTenantHost(host: TenantHost) = {
    transactionCheckQuota { implicit connection =>
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
    // Allow over quota, so you're over quota emails get sent.
    transactionAllowOverQuota { implicit connection =>
      _saveUnsentEmail(email)
      updateNotificationConnectToEmail(notfs, Some(email))
    }
  }


  def saveUnsentEmail(email: Email) {
    // Allow over quota, so you're over quota emails get sent.
    transactionAllowOverQuota { _saveUnsentEmail(email)(_) }
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
    transactionAllowOverQuota { implicit connection =>

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
        dieIf(anyGuestId == Some("1"), "DwE4GKS2", "Guest id matches system user id")

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


  def loadPagePath(pageId: PageId): Option[PagePath] =
    lookupPagePathImpl(pageId)(theOneAndOnlyConnection)


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


  def insertPageMeta(pageMeta: PageMeta) {
    require(pageMeta.creationDati == pageMeta.modDati)
    pageMeta.pubDati.foreach(publDati =>
      require(pageMeta.creationDati.getTime <= publDati.getTime))

    val sql = """
      insert into DW1_PAGES (
         SNO, TENANT, GUID, PAGE_ROLE, PARENT_PAGE_ID, EMBEDDING_PAGE_URL,
         CDATI, MDATI, PUBL_DATI,
         CACHED_AUTHOR_USER_ID, CACHED_LAST_VISIBLE_POST_DATI)
      values (
         nextval('DW1_PAGES_SNO'), ?, ?, ?, ?, ?,
         ?, ?, ?,
         ?, ?)"""

    val values = List[AnyRef](
      siteId, pageMeta.pageId,
      _pageRoleToSql(pageMeta.pageRole), pageMeta.parentPageId.orNullVarchar,
      pageMeta.embeddingPageUrl.orNullVarchar,
      d2ts(pageMeta.creationDati), d2ts(pageMeta.modDati), o2ts(pageMeta.pubDati),
      pageMeta.cachedAuthorUserId, o2ts(pageMeta.cachedLastVisiblePostDati))

    val numNewRows = runUpdate(sql, values)

    if (numNewRows == 0) {
      // If the problem was a primary key violation, we wouldn't get to here.
      runErr("DwE48GS3", o"""Cannot create a `${pageMeta.pageRole}' page because
        the parent page, id `${pageMeta.parentPageId}', has an incompatible role""")
    }

    if (2 <= numNewRows)
      die("DwE45UL8") // there's a primary key on site + page id
  }


  def insertPagePath(pagePath: PagePath): Unit = {
    insertPagePathOrThrow(pagePath)(theOneAndOnlyConnection)
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
    transactionCheckQuota { implicit connection =>
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
    transactionCheckQuota { implicit connection =>
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


  def rememberPostsAreIndexed(indexedVersion: Int, pageAndPostIds: PagePostId*) {
    val pagesAndPostsClause =
      pageAndPostIds.map(_ => "(PAGE_ID = ? and PAID = ?)").mkString(" or ")

    unimplemented("Indexing posts in DW2_POSTS", "DwE0GIK3") // this uses a deleted table:
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

}



