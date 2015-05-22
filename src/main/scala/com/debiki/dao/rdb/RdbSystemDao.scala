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
import com.debiki.core.PagePath._
import com.debiki.core.DbDao._
import com.debiki.core.EmailNotfPrefs.EmailNotfPrefs
import com.debiki.core.Prelude._
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import java.{sql => js}
import org.flywaydb.core.Flyway
import scala.{collection => col}
import scala.collection.{mutable => mut, immutable}
import scala.collection.mutable.StringBuilder
import Rdb._
import RdbUtil._
import NotificationsSiteDaoMixin.flagToEmailStatus
import PageSiteDaoMixin.fromActionTypeInt


class RdbSystemDao(val daoFactory: RdbDaoFactory)
  extends SystemDbDao with CreateSiteSystemDaoMixin with SystemTransaction {

  def db = daoFactory.db

  def close() { db.close() }


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
      throw new IllegalStateException("Transaction has ended [DwE5KD3W2]")
    _theOneAndOnlyConnection getOrElse {
      die("DwE4HKG81")
    }
  }
  private var _theOneAndOnlyConnection: Option[js.Connection] = None

  // COULD move to new superclass?
  private var transactionEnded = false

  def setTheOneAndOnlyConnection(connection: js.Connection) {
    require(_theOneAndOnlyConnection.isEmpty, "DwE7PKF2")
    _theOneAndOnlyConnection = Some(connection)
  }

  def createTheOneAndOnlyConnection(readOnly: Boolean) {
    require(_theOneAndOnlyConnection.isEmpty, "DwE8PKW2")
    _theOneAndOnlyConnection = Some(db.getConnection(readOnly))
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


  def withConnection[T](f: (js.Connection) => T): T = {
    anyOneAndOnlyConnection foreach { connection =>
      return f(connection)
    }
    db.withConnection(f)
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


  def transaction[T](f: (js.Connection) => T): T = {
    anyOneAndOnlyConnection foreach { connection =>
      return f(connection)
    }
    db.transaction(f)
  }


  override def siteTransaction(siteId: SiteId): SiteTransaction = {
    val siteTransaction = new RdbSiteDao(siteId, daoFactory)
    siteTransaction.setTheOneAndOnlyConnection(theOneAndOnlyConnection)
    siteTransaction
  }


  def checkRepoVersion() = unimplemented

  def secretSalt(): String = unimplemented

  /** Creates a site specific dao. */
  def newSiteDao(siteId: SiteId): RdbSiteDao =
    daoFactory.newSiteDbDao(siteId)


  lazy val currentTime: ju.Date = {
    runQuery("select now_utc() as current_time", Nil, rs => {
      rs.next()
      ts2d(rs.getTimestamp("current_time"))
    })
  }


  def loadUser(siteId: SiteId, userId: UserId): Option[User] = {
    val userBySiteUserId = loadUsers(Map(siteId -> List(userId)))
    userBySiteUserId.get((siteId, userId))
  }


  // private [this dao package]
  def loadUsers(userIdsByTenant: Map[String, List[String]])
        : Map[(String, String), User] = {

    var idCount = 0
    var longestInList = 0

    def incIdCount(ids: List[String]) {
      val len = ids.length
      idCount += len
      if (len > longestInList) longestInList = len
    }

    def mkQueryUnau(tenantId: String, idsUnau: List[String])
          : (String, List[String]) = {
      incIdCount(idsUnau)
      val inList = idsUnau.map(_ => "?").mkString(",")
      // Use "u_*" select list item names, so works with _User(result-set).
      val q = """
         select
            g.SITE_ID SITE_ID, '-'||g.ID u_id, g.NAME u_disp_name, null u_username,
            null u_created_at,
            g.EMAIL_ADDR u_email, e.EMAIL_NOTFS u_email_notfs,
            null u_email_verified_at,
            null u_password_hash,
            g.LOCATION u_country,
            g.URL u_website, 'F' u_superadmin, 'F' u_is_owner
         from
           DW1_GUESTS g left join DW1_IDS_SIMPLE_EMAIL e
           on g.SITE_ID = e.SITE_ID and g.EMAIL_ADDR = e.EMAIL
         where
           g.SITE_ID = ? and
           g.ID in (""" + inList +")"
      // A guest user id starts with '-', drop it.
      val vals = tenantId :: idsUnau.map(_.drop(1))
      (q, vals)
    }

    def mkQueryAu(tenantId: String, idsAu: List[String])
          : (String, List[String]) = {
      incIdCount(idsAu)
      val inList = idsAu.map(_ => "?").mkString(",")
      val q = """
         select u.SITE_ID, """+ _UserSelectListItems +"""
         from DW1_USERS u
         where u.SITE_ID = ?
         and u.SNO in (""" + inList +")"
      (q, tenantId :: idsAu)
    }

    val totalQuery = StringBuilder.newBuilder
    var allValsReversed = List[String]()

    def growQuery(moreQueryAndVals: (String, List[String])) {
      if (totalQuery.nonEmpty)
        totalQuery ++= " union "
      totalQuery ++= moreQueryAndVals._1
      allValsReversed = moreQueryAndVals._2.reverse ::: allValsReversed
    }

    // Build query.
    for ((tenantId, userIds) <- userIdsByTenant.toList) {
      val (idsUnau, idsAu) = userIds.distinct.partition(User.isGuestId)

      if (idsUnau nonEmpty) growQuery(mkQueryUnau(tenantId, idsUnau))
      if (idsAu nonEmpty) growQuery(mkQueryAu(tenantId, idsAu))
    }

    if (idCount == 0)
      return Map.empty

    // Could log warning if longestInList > 1000, would break in Oracle
    // (max in list size is 1000).

    var usersByTenantAndId = Map[(String, String), User]()

    db.queryAtnms(totalQuery.toString, allValsReversed.reverse, rs => {
      while (rs.next) {
        val tenantId = rs.getString("SITE_ID")
        // Sometimes convert both "-" and null to "", because unauthenticated
        // users use "-" as placeholder for "nothing specified" -- so those
        // values are indexed (since sql null isn't).
        // Authenticated users, however, currently use sql null for nothing.
        val user = _User(rs)
        usersByTenantAndId = usersByTenantAndId + ((tenantId, user.id) -> user)
      }
    })

    usersByTenantAndId
  }


  override def loadSites(): immutable.Seq[Tenant] =
    loadSitesImpl(all = true).to[immutable.Seq]


  def loadTenants(tenantIds: Seq[String]): Seq[Tenant] =
    if (tenantIds.isEmpty) Nil
    else loadSitesImpl(tenantIds = tenantIds)


  def loadSitesImpl(tenantIds: Seq[String] = Nil, all: Boolean = false): Seq[Tenant] = {
    // For now, load only 1 tenant.
    require(tenantIds.length == 1 || all)

    var hostsByTenantId = Map[String, List[TenantHost]]().withDefaultValue(Nil)
    var hostsQuery = "select SITE_ID, HOST, CANONICAL, HTTPS from DW1_TENANT_HOSTS"
    var hostsValues: List[AnyRef] = Nil
    if (!all) {
      UNTESTED
      hostsQuery += " where SITE_ID = ?" // for now, later: in (...)
      hostsValues = List(tenantIds.head)
    }
    queryAtnms(hostsQuery, hostsValues, rs => {
        while (rs.next) {
          val tenantId = rs.getString("SITE_ID")
          var hosts = hostsByTenantId(tenantId)
          hosts ::= TenantHost(
             address = rs.getString("HOST"),
             role = _toTenantHostRole(rs.getString("CANONICAL")),
             https = _toTenantHostHttps(rs.getString("HTTPS")))
          hostsByTenantId = hostsByTenantId.updated(tenantId, hosts)
        }
      })

    var sitesQuery =
      "select ID, NAME, EMBEDDING_SITE_URL, CREATOR_IP, CREATOR_EMAIL_ADDRESS from DW1_TENANTS"
    var sitesValues: List[AnyRef] = Nil
    if (!all) {
      sitesQuery += " where ID = ?"  // for now, later: in (...)
      sitesValues = List(tenantIds.head)
    }
    var tenants = List[Tenant]()
    queryAtnms(sitesQuery, sitesValues, rs => {
      while (rs.next) {
        val tenantId = rs.getString("ID")
        val hosts = hostsByTenantId(tenantId)
        tenants ::= Tenant(
          id = tenantId,
          name = rs.getString("NAME"),
          creatorIp = rs.getString("CREATOR_IP"),
          creatorEmailAddress = rs.getString("CREATOR_EMAIL_ADDRESS"),
          embeddingSiteUrl = Option(rs.getString("EMBEDDING_SITE_URL")),
          hosts = hosts)
      }
    })
    tenants
  }


  def lookupTenant(scheme: String, host: String): TenantLookup = {
    val RoleCanonical = "C"
    val RoleLink = "L"
    val RoleRedirect = "R"
    val RoleDuplicate = "D"
    val HttpsRequired = "R"
    val HttpsAllowed = "A"
    val HttpsNo = "N"
    db.queryAtnms("""
        select t.SITE_ID TID,
            t.CANONICAL THIS_CANONICAL, t.HTTPS THIS_HTTPS,
            c.HOST CANONICAL_HOST, c.HTTPS CANONICAL_HTTPS
        from DW1_TENANT_HOSTS t -- this host, the one connected to
            left join DW1_TENANT_HOSTS c  -- the cannonical host
            on c.SITE_ID = t.SITE_ID and c.CANONICAL = 'C'
        where t.HOST = ?
        """, List(host), rs => {
      if (!rs.next) {
        if (Site.Ipv4AnyPortRegex.matches(host)) {
          // Make it possible to assess the server before any domain has been connected
          // to it and when we stil don't know its ip, just after installation.
          return FoundAlias(Site.FirstSiteId, canonicalHostUrl = "",
            role = TenantHost.RoleDuplicate)
        }
        else {
          return FoundNothing
        }
      }
      val tenantId = rs.getString("TID")
      val thisHttps = rs.getString("THIS_HTTPS")
      val (thisRole, chost, chostHttps) = {
        var thisRole = rs.getString("THIS_CANONICAL")
        var chost_? = rs.getString("CANONICAL_HOST")
        var chostHttps_? = rs.getString("CANONICAL_HTTPS")
        if (thisRole == RoleDuplicate) {
          // Pretend this is the chost.
          thisRole = RoleCanonical
          chost_? = host
          chostHttps_? = thisHttps
        }
        if (chost_? eq null) {
          // This is not a duplicate, and there's no canonical host
          // to link or redirect to.
          return FoundNothing
        }
        (thisRole, chost_?, chostHttps_?)
      }

      def chostUrl =  // the canonical host URL, e.g. http://www.example.com
          (if (chostHttps == HttpsRequired) "https://" else "http://") + chost

      assErrIf3((thisRole == RoleCanonical) != (host == chost), "DwE98h1215]")

      def useThisHostAndScheme = FoundChost(tenantId)
      def redirect = FoundAlias(tenantId, canonicalHostUrl = chostUrl,
                              role = TenantHost.RoleRedirect)
      def useLinkRelCanonical = redirect.copy(role = TenantHost.RoleLink)

      (thisRole, scheme, thisHttps) match {
        case (RoleCanonical, "http" , HttpsRequired) => redirect
        case (RoleCanonical, "http" , _            ) => useThisHostAndScheme
        case (RoleCanonical, "https", HttpsRequired) => useThisHostAndScheme
        case (RoleCanonical, "https", HttpsAllowed ) => useLinkRelCanonical
        case (RoleCanonical, "https", HttpsNo      ) => redirect
        case (RoleRedirect , _      , _            ) => redirect
        case (RoleLink     , _      , _            ) => useLinkRelCanonical
        case (RoleDuplicate, _      , _            ) => assErr("DwE09KL04")
      }
    })
  }


  def loadNotificationsToMailOut(delayInMinutes: Int, numToLoad: Int)
        : Map[SiteId, Seq[Notification]] =
    loadNotfsImpl(numToLoad, None, delayMinsOpt = Some(delayInMinutes))


  /**
   * Specify:
   * numToLoad + delayMinsOpt --> loads notfs to mail out, for all tenants
   * tenantIdOpt + userIdOpt --> loads that user's notfs
   * tenantIdOpt + emailIdOpt --> loads a single email and notf
   */
  def loadNotfsImpl(numToLoad: Int, tenantIdOpt: Option[String] = None,
        delayMinsOpt: Option[Int] = None, userIdOpt: Option[String] = None,
        emailIdOpt: Option[String] = None): Map[SiteId, Seq[Notification]] = {

    require(emailIdOpt.isEmpty, "looking up by email id not tested after rewrite")
    require(delayMinsOpt.isEmpty || userIdOpt.isEmpty)
    require(delayMinsOpt.isEmpty || emailIdOpt.isEmpty)
    require(userIdOpt.isEmpty || emailIdOpt.isEmpty)
    require(delayMinsOpt.isDefined != tenantIdOpt.isDefined)
    require(userIdOpt.isEmpty || tenantIdOpt.isDefined)
    require(emailIdOpt.isEmpty || tenantIdOpt.isDefined)
    require(numToLoad > 0)
    require(emailIdOpt.isEmpty || numToLoad == 1)

    val baseQuery = """
      select
        SITE_ID, NOTF_TYPE, CREATED_AT,
        UNIQUE_POST_ID, PAGE_ID, POST_ID, ACTION_TYPE, ACTION_SUB_ID,
        BY_USER_ID, TO_USER_ID,
        EMAIL_ID, EMAIL_STATUS, SEEN_AT
      from DW1_NOTIFICATIONS
      where """

    val (whereOrderBy, values) = (userIdOpt, emailIdOpt) match {
      case (Some(uid), None) =>
        // Later on, could choose to load only those not yet seen.
        var whereOrderBy =
          "SITE_ID = ? and TO_USER_ID = ? order by CREATED_AT desc"
        val vals = List(tenantIdOpt.get, uid.toInt.asAnyRef)  // UserId2
        (whereOrderBy, vals)
      case (None, Some(emailId)) =>
        val whereOrderBy = "SITE_ID = ? and EMAIL_ID = ?"
        val vals = List(tenantIdOpt.get, emailId)
        (whereOrderBy, vals)
      case (None, None) =>
        // Load notfs for which emails perhaps are to be sent, for all tenants.
        val whereOrderBy =
          "EMAIL_STATUS = 'U' and CREATED_AT <= ? order by CREATED_AT asc"
        val nowInMillis = (new ju.Date).getTime
        val someMinsAgo =
          new ju.Date(nowInMillis - delayMinsOpt.get.toLong * 60 * 1000)
        val vals = someMinsAgo::Nil
        (whereOrderBy, vals)
      case _ =>
        assErr("DwE093RI3")
    }

    val query = baseQuery + whereOrderBy +" limit "+ numToLoad
    var notfsByTenant =
       Map[SiteId, List[Notification]]().withDefaultValue(Nil)

    db.queryAtnms(query, values, rs => {
      while (rs.next) {
        val siteId = rs.getString("SITE_ID")
        val notfTypeStr = rs.getString("NOTF_TYPE")
        val createdAt = ts2d(rs.getTimestamp("CREATED_AT"))
        val uniquePostId = rs.getInt("UNIQUE_POST_ID")
        val pageId = rs.getString("PAGE_ID")
        val postId = rs.getInt("POST_ID")
        val actionType = getResultSetIntOption(rs, "ACTION_TYPE").map(fromActionTypeInt)
        val actionSubId = getResultSetIntOption(rs, "ACTION_SUB_ID")
        val byUserId = rs.getInt("BY_USER_ID").toString // UserId2
        val toUserId = rs.getInt("TO_USER_ID").toString // UserId2
        val emailId = Option(rs.getString("EMAIL_ID"))
        val emailStatus = flagToEmailStatus(rs.getString("EMAIL_STATUS"))
        val seenAt = ts2o(rs.getTimestamp("SEEN_AT"))

        val notification = notfTypeStr match {
          case "R" | "M" | "N" =>
            val newPostType = notfTypeStr match {
              case "R" => Notification.NewPostNotfType.DirectReply
              case "M" => Notification.NewPostNotfType.Mention
              case "N" => Notification.NewPostNotfType.NewPost
            }
            Notification.NewPost(
              notfType = newPostType,
              siteId = siteId,
              createdAt = createdAt,
              uniquePostId = uniquePostId,
              pageId = pageId,
              postId = postId,
              byUserId = byUserId,
              toUserId = toUserId,
              emailId = emailId,
              emailStatus = emailStatus,
              seenAt = seenAt)
        }

        val notfsForTenant: List[Notification] = notfsByTenant(siteId)
        notfsByTenant = notfsByTenant + (siteId -> (notification::notfsForTenant))
      }
    })

    notfsByTenant
  }


  def findPostsNotYetIndexed(currentIndexVersion: Int, limit: Int): Seq[PostsToIndex] = {
    db.withConnection { connection =>
      findPostsNotYetIndexedImpl(currentIndexVersion, limit)(connection)
    }
  }


  private def findPostsNotYetIndexedImpl(currentIndexVersion: Int, limit: Int)(
        connection: js.Connection): Seq[PostsToIndex] = {

    // First load ids of posts to index, then load pages and posts.
    //
    // (We need to load metadata on each page, actually, because
    // when indexing a post, we want to know the page-id-path to the page
    // to which the post belongs, so we can index this page-id-path,
    // because then we can restrict the search to a subsection of the site
    // (namely below a certain page id).)

    val postIdsByPageBySite = loadIdsOfPostsToIndex(currentIndexVersion, limit)(connection)
    loadPostsToIndex(postIdsByPageBySite)(connection)
  }


  private def loadIdsOfPostsToIndex(
        currentIndexVersion: Int, limit: Int)(connection: js.Connection)
        : col.Map[SiteId, col.Map[PageId, col.Seq[PostId]]] = {

    val postIdsByPageBySite = mut.Map[SiteId, mut.Map[PageId, mut.ArrayBuffer[PostId]]]()

    // `currentIndexVersion` shouldn't change until server restarted.
    unimplemented("Indexing posts in DW2_POSTS", "DwE4KUPY8") // this uses a deleted table:
    val sql = s"""
      select SITE_ID, PAGE_ID, POST_ID from DW1_PAGE_ACTIONS
      where TYPE = 'Post' and INDEX_VERSION <> $currentIndexVersion
      order by SITE_ID, PAGE_ID
      limit $limit
      """

    db.query(sql, Nil, rs => {
      while (rs.next) {
        val siteId = rs.getString("SITE_ID")
        val pageId = rs.getString("PAGE_ID")
        val postId = rs.getInt("POST_ID")
        if (postIdsByPageBySite.get(siteId).isEmpty) {
          postIdsByPageBySite(siteId) = mut.Map[PageId, mut.ArrayBuffer[PostId]]()
        }
        if (postIdsByPageBySite(siteId).get(pageId).isEmpty) {
          postIdsByPageBySite(siteId)(pageId) = mut.ArrayBuffer[PostId]()
        }
        postIdsByPageBySite(siteId)(pageId) += postId
      }
    })(connection)

    postIdsByPageBySite
  }


  private def loadPostsToIndex(
        postIdsByPageBySite: col.Map[SiteId, col.Map[PageId, col.Seq[PostId]]])(
        connection: js.Connection): Vector[PostsToIndex] = {

    var chunksOfPostsToIndex = Vector[PostsToIndex]()

    for ((siteId, postIdsByPage) <- postIdsByPageBySite.iterator) {
      val siteDao: RdbSiteDao = newSiteDao(siteId)
      val pageIds = postIdsByPage.keySet.toList
      val ancestorIdsByPageId: col.Map[PageId, List[PageId]] =
        siteDao.batchLoadAncestorIdsParentFirst(pageIds)(connection)
      val metaByPageId: Map[PageId, PageMeta] = siteDao.loadPageMetaImpl(pageIds)(connection)

      // This is really inefficient, but load the whole page. Then all
      // posts that we are to index will also be loaded.
      // — This works even if the posts have not yet been inserted into DW1_POSTS
      // (which is currently the case).
      // COULD try to load an up-to-date version from DW1_POSTS first (so the
      // history of each post needn't be loaded too).
      val pageParts: List[PageParts] = pageIds.flatMap(siteDao.loadPageParts(_).toList)

      for {
        (pageId, postIds) <- postIdsByPage.iterator
        parts <- pageParts.find(_.pageId == pageId)
        meta <- metaByPageId.get(pageId)
        ancestorIds <- ancestorIdsByPageId.get(pageId)
      } {
        val pageNoPath = PageNoPath(parts, ancestorIds, meta)
        val posts = postIds.flatMap(parts.getPost(_))
        chunksOfPostsToIndex :+= PostsToIndex(siteId, pageNoPath, posts.toVector)
      }
    }

    chunksOfPostsToIndex
  }


  /** Finds all evolution scripts below src/main/resources/db/migration and applies them.
    */
  def applyEvolutions() {
    val flyway = new Flyway()

    // --- Temporarily, to initialize the production database -----
    flyway.setInitDescription("base version")
    flyway.setInitOnMigrate(true)
    // ------------------------------------------------------------

    flyway.setDataSource(db.dataSource)
    flyway.setSchemas("public")
    // Default prefix is uppercase "V" but I want files in lowercase, e.g. v1__migration_name.sql.
    flyway.setSqlMigrationPrefix("v")
    // Warning: Don't clean() in production, could wipe out all data.
    flyway.setCleanOnValidationError(daoFactory.isTest)
    // Make this DAO accessible to the Scala code in the Flyway migration.
    _root_.db.migration.MigrationHelper.systemDbDao = this
    _root_.db.migration.MigrationHelper.scalaBasedMigrations = daoFactory.migrations
    flyway.migrate()
  }


  override def emptyDatabase() {
    require(daoFactory.isTest)
    db.transaction { implicit connection =>

      // There are foreign keys from DW1_TENANTS to other tables, and
      // back.
      db.update("SET CONSTRAINTS ALL DEFERRED");

      s"""
      delete from DW1_SETTINGS
      delete from DW1_ROLE_PAGE_SETTINGS
      delete from DW1_POSTS_READ_STATS
      delete from DW1_NOTIFICATIONS
      delete from DW1_EMAILS_OUT
      delete from DW2_POST_ACTIONS
      delete from DW1_PATHS
      delete from DW1_PAGE_PATHS
      delete from DW1_PAGES
      delete from DW2_POSTS
      delete from DW1_IDS_SIMPLE_EMAIL
      delete from DW1_GUESTS
      delete from DW1_IDS_OPENID
      delete from DW1_USERS
      delete from DW1_TENANT_HOSTS
      delete from DW1_TENANTS where ID <> '${Site.FirstSiteId}'
      update DW1_TENANTS set NEXT_PAGE_ID = 1
      alter sequence DW1_IDS_SNO restart
      alter sequence DW1_PAGES_SNO restart
      alter sequence DW1_TENANTS_ID restart
      alter sequence DW1_USERS_SNO restart
      """.trim.split("\n") foreach { db.update(_) }

      db.update("SET CONSTRAINTS ALL IMMEDIATE")
    }
  }

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list

