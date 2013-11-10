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
import com.debiki.core.Prelude._
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import java.{sql => js}
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
  extends SiteDbDao with FullTextSearchSiteDaoMixin with LoginSiteDaoMixin {


  val MaxWebsitesPerIp = 6

  def siteId = quotaConsumers.tenantId

  def db = systemDaoSpi.db

  def systemDaoSpi = daoFactory.systemDbDao

  def fullTextSearchIndexer = daoFactory.fullTextSearchIndexer


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
      pagePerhapsId.copyWithNewId(nextRandomPageId)  // COULD ensure same
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
    val (newPageNoPath, actionDtosWithIds) =
      savePageActionsImpl(emptyPage, page.parts.actionDtos)(connection)

    Page(newPageNoPath.meta, page.path, page.ancestorIdsParentFirst, newPageNoPath.parts)
  }


  def loadPageMeta(pageId: PageId): Option[PageMeta] = {
    loadPageMetas(pageId::Nil) get pageId
  }


  def loadPageMetas(pageIds: Seq[PageId]): Map[PageId, PageMeta] = {
    if (pageIds.isEmpty) return Map.empty
    db.withConnection { loadPageMetaImpl(pageIds)(_) }
  }


  def loadPageMetaImpl(pageIds: Seq[PageId])(connection: js.Connection)
        : Map[PageId, PageMeta] = {
    assErrIf(pageIds.isEmpty, "DwE84KF0")
    val values = siteId :: pageIds.toList
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
      d2ts(newMeta.modDati),
      o2ts(newMeta.pubDati),
      o2ts(newMeta.sgfntModDati),
      newMeta.cachedTitle.orNullVarchar,
      newMeta.cachedAuthorDispName orIfEmpty NullVarchar,
      newMeta.cachedAuthorUserId orIfEmpty NullVarchar,
      newMeta.cachedNumPosters.asInstanceOf[AnyRef],
      newMeta.cachedNumActions.asInstanceOf[AnyRef],
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
        MDATI = ?,
        PUBL_DATI = ?,
        SGFNT_MDATI = ?,
        CACHED_TITLE = ?,
        CACHED_AUTHOR_DISPLAY_NAME = ?,
        CACHED_AUTHOR_USER_ID = ?,
        CACHED_NUM_POSTERS = ?,
        CACHED_NUM_ACTIONS = ?,
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
          val pageIdPathSelfFirst = sqlArray.getArray.asInstanceOf[Array[String]].toList
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


  def movePages(pageIds: Seq[String], fromFolder: String, toFolder: String) {
    db.transaction { implicit connection =>
      _movePages(pageIds, fromFolder = fromFolder, toFolder = toFolder)
    }
  }


  private def _movePages(pageIds: Seq[String], fromFolder: String,
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


  def moveRenamePage(pageId: String,
        newFolder: Option[String], showId: Option[Boolean],
        newSlug: Option[String]): PagePath = {
    db.transaction { implicit connection =>
      moveRenamePageImpl(pageId, newFolder = newFolder, showId = showId,
         newSlug = newSlug)
    }
  }



  private[rdb] def _insertUser(tenantId: String, userNoId: User)
        (implicit connection: js.Connection): User = {
    val userSno = db.nextSeqNo("DW1_USERS_SNO")
    val user = userNoId.copy(id = userSno.toString)
    db.update("""
        insert into DW1_USERS(
            TENANT, SNO, DISPLAY_NAME, EMAIL, EMAIL_NOTFS, COUNTRY,
            SUPERADMIN, IS_OWNER)
        values (?, ?, ?, ?, ?, ?, ?, ?)""",
        List[AnyRef](tenantId, user.id, e2n(user.displayName),
           e2n(user.email), _toFlag(user.emailNotfPrefs), e2n(user.country),
           tOrNull(user.isAdmin), tOrNull(user.isOwner)))
    user
  }


  private[rdb] def insertIdentity(identityNoId: Identity, userId: UserId, otherSiteId: String)
        (connection: js.Connection): Identity = {
    identityNoId match {
      case x: IdentityOpenId =>
        insertOpenIdIdentity(siteId, x.copy(id = "?", userId = userId))(connection)
      case x: PasswordIdentity =>
        insertPasswordIdentity(otherSiteId, x.copy(id = "?", userId = userId))(connection)
      case x =>
        assErr(s"Don't know how to insert identity of type: ${classNameOf(x)}")
    }
  }


  private[rdb] def insertOpenIdIdentity(tenantId: String, idtyNoId: Identity)
        (implicit connection: js.Connection): Identity = {
    val newIdentityId = db.nextSeqNo("DW1_IDS_SNO").toString
    idtyNoId match {
      case oidIdtyNoId: IdentityOpenId =>
        val idty = oidIdtyNoId.copy(id = newIdentityId)
        val details = oidIdtyNoId.openIdDetails
        db.update("""
            insert into DW1_IDS_OPENID(
                SNO, TENANT, USR, USR_ORIG, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                OID_REALM, OID_ENDPOINT, OID_VERSION,
                FIRST_NAME, EMAIL, COUNTRY)
            values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            List[AnyRef](idty.id, tenantId, idty.userId, idty.userId,
              details.oidClaimedId, e2d(details.oidOpLocalId), e2d(details.oidRealm),
              e2d(details.oidEndpoint), e2d(details.oidVersion),
              e2d(details.firstName), e2d(details.email), e2d(details.country)))
        idty
      case _ =>
        assErr("DwE03IJL2")
    }
  }


  private[rdb] def insertPasswordIdentity(otherSiteId: SiteId, identityNoId: PasswordIdentity)
        (implicit connection: js.Connection): PasswordIdentity = {
    val newIdentityId = db.nextSeqNo("DW1_IDS_SNO").toString
    val identity = identityNoId.copy(id = newIdentityId)
    db.update("""
        insert into DW1_IDS_OPENID(
            SNO, TENANT, USR, USR_ORIG, EMAIL, PASSWORD_HASH)
        values (
            ?, ?, ?, ?, ?, ?)""",
        List[AnyRef](identity.id, otherSiteId, identity.userId, identity.userId,
          identity.email, identity.passwordSaltHash))
    identity
  }


  private[rdb] def _updateIdentity(identity: IdentityOpenId)
        (implicit connection: js.Connection) {
    val details = identity.openIdDetails
    db.update("""
      update DW1_IDS_OPENID set
          USR = ?, OID_CLAIMED_ID = ?,
          OID_OP_LOCAL_ID = ?, OID_REALM = ?,
          OID_ENDPOINT = ?, OID_VERSION = ?,
          FIRST_NAME = ?, EMAIL = ?, COUNTRY = ?
      where SNO = ? and TENANT = ?
      """,
      List[AnyRef](identity.userId, details.oidClaimedId,
        e2d(details.oidOpLocalId), e2d(details.oidRealm),
        e2d(details.oidEndpoint), e2d(details.oidVersion),
        e2d(details.firstName), e2d(details.email), e2d(details.country),
        identity.id, siteId))
  }


  override def saveLogout(loginId: String, logoutIp: String) {
    require(loginId != SystemUser.Login.id)
    db.transaction { implicit connection =>
      db.update("""
          update DW1_LOGINS set LOGOUT_IP = ?, LOGOUT_TIME = ?
          where SNO = ?""", List(logoutIp, new ju.Date, loginId)) match {
        case 1 => ()
        case x => assErr("DwE0kSRIE3", "Updated "+ x +" rows")
      }
    }
  }


  private def _loadLoginById(loginId: String)
        (implicit connection: js.Connection): Option[Login] = {
    val logins = _loadLogins(byLoginIds = loginId::Nil)
    assErrIf(logins.length > 1, "DwE47IB6")
    logins.headOption
  }


  private def _loadLogins(byLoginIds: List[String] = null,
        onPageGuid: String = null)
        (implicit connection: js.Connection): List[Login] = {

    assert((byLoginIds ne null) ^ (onPageGuid ne null))

    val selectList = """
        select
            l.SNO LOGIN_SNO, l.PREV_LOGIN,
            l.ID_TYPE, l.ID_SNO,
            l.LOGIN_IP, l.LOGIN_TIME,
            l.LOGOUT_IP, l.LOGOUT_TIME
        """

    val (fromWhereClause, pageOrLoginIds) =
      if (onPageGuid ne null)
        ("""from DW1_PAGE_ACTIONS a, DW1_LOGINS l
          where a.TENANT = ?
            and a.PAGE_ID = ?
            and l.TENANT = a.TENANT
            and l.SNO = a.LOGIN""", onPageGuid::Nil)
      else if (byLoginIds isEmpty)
        return Nil
      else
        ("""from DW1_LOGINS l
          where l.TENANT = ?
            and l.SNO in ("""+ makeInListFor(byLoginIds) +")",
           byLoginIds)

    var logins = List[Login]()

    db.queryAtnms(selectList + fromWhereClause,
        siteId :: pageOrLoginIds, rs => {
      while (rs.next) {
        val loginId = rs.getString("LOGIN_SNO")
        val prevLogin = Option(rs.getString("PREV_LOGIN"))
        val ip = rs.getString("LOGIN_IP")
        val date = ts2d(rs.getTimestamp("LOGIN_TIME"))
        val identityRef = makeIdentityRef(rs.getString("ID_TYPE"), id = rs.getString("ID_SNO"))
        logins ::= Login(id = loginId, prevLoginId = prevLogin, ip = ip,
          date = date, identityRef = identityRef)
      }
    })

    logins
  }


  def loadIdtyDetailsAndUser(
        forLoginId: String = null,
        forOpenIdDetails: OpenIdDetails = null,
        forEmailAddr: String = null): Option[(Identity, User)] = {
    db.withConnection(implicit connection => {
      _loadIdtyDetailsAndUser(
          forLoginId = forLoginId,
          forOpenIdDetails = forOpenIdDetails,
          forEmailAddr = forEmailAddr) match {
        case (None, None) => None
        case (Some(idty), Some(user)) => Some(idty, user)
        case (None, user) => assErr("DwE257IV2")
        case (idty, None) => assErr("DwE6BZl42")
      }
    })
  }


  /** Looks up detailed info on a single user. Only one forXxx param may be specified.
    */
  // COULD return Option(identity, user) instead of (Opt(id), Opt(user)).
  private[rdb] def _loadIdtyDetailsAndUser(
        forLoginId: String = null,
        forOpenIdDetails: OpenIdDetails = null,
        forEmailAddr: String = null)(implicit connection: js.Connection)
        : (Option[Identity], Option[User]) = {

    val anyOpenIdDetails = Option(forOpenIdDetails)

    val loginOpt: Option[Login] =
      if (forLoginId eq null) None
      else Some(_loadLoginById(forLoginId) getOrElse {
        return None -> None
      })

    val anyEmail = Option(forEmailAddr)

    var sqlSelectFrom = """
        select
            """+ _UserSelectListItems +""",
            i.SNO i_id,
            i.OID_CLAIMED_ID,
            i.OID_OP_LOCAL_ID,
            i.OID_REALM,
            i.OID_ENDPOINT,
            i.OID_VERSION,
            i.FIRST_NAME i_first_name,
            i.EMAIL i_email,
            i.PASSWORD_HASH,
            i.COUNTRY i_country
          from DW1_IDS_OPENID i inner join DW1_USERS u
            on i.TENANT = u.TENANT
            and i.USR = u.SNO
          """

    val (whereClause, bindVals) = (loginOpt, anyEmail, anyOpenIdDetails) match {
      case (Some(login: Login), None, None) =>
        ("""where i.TENANT = ? and i.SNO = ?""",
           List(siteId, login.identityRef.identityId))

      case (None, Some(email), None) =>
        ("""where i.TENANT = ? and i.EMAIL = ?""",
          List(siteId, email))

      case (None, None, Some(openIdDetails: OpenIdDetails)) =>
        // With Google OpenID, the identifier varies by realm. So use email
        // address instead. (With Google OpenID, the email address can be
        // trusted — this is not the case, however, in general.
        // See: http://blog.stackoverflow.com/2010/04/openid-one-year-later/
        // Quote:
        //  "If we have an email address from a verified OpenID email
        //   provider (that is, an OpenID from a large email service we trust,
        //   like Google or Yahoo), then it’s guaranteed to be a globally
        //   unique string.")
        val (claimedIdOrEmailCheck, idOrEmail) = {
          // SECURITY why can I trust the OpenID provider to specify
          // the correct endpoint? What if Mallory's provider replies
          // with Googles endpoint? I guess the Relying Party impl doesn't
          // allow this but anyway, I'd like to know for sure.
          if (openIdDetails.isGoogleLogin)
            ("i.OID_ENDPOINT = '"+ IdentityOpenId.GoogleEndpoint +
               "' and i.EMAIL = ?", openIdDetails.email)
          else
            ("i.OID_CLAIMED_ID = ?", openIdDetails.oidClaimedId)
        }
        ("""where i.TENANT = ?
            and """+ claimedIdOrEmailCheck +"""
          """, List(siteId, idOrEmail))

      case _ => assErr("DwE98239k2a2", "None, or more than one, lookup method specified")
    }

    db.query(sqlSelectFrom + whereClause, bindVals, rs => {
      if (!rs.next)
        return None -> None

      // Warning: Some dupl code in _loadIdtysAndUsers:
      // COULD break out construction of Identity to reusable
      // functions.

      val userInDb = _User(rs)

      val id = rs.getLong("i_id").toString
      val email = rs.getString("i_email")
      val anyPasswordHash = Option(rs.getString("PASSWORD_HASH"))

      val identityInDb = {
        if (anyPasswordHash.nonEmpty) {
          if (anyOpenIdDetails.isDefined) throwBadDatabaseData(
            "DwE7IER2", s"Password hash found for OpenID user, site: $siteId, id: $id")

          PasswordIdentity(
            id = id,
            userId = userInDb.id,
            email = email,
            passwordSaltHash = anyPasswordHash.get)
        }
        else {
          if (anyEmail.isDefined) throwBadDatabaseData(
            "DwE61ZW8", s"Row without password found for password user, site: $siteId, id: $id")

          IdentityOpenId(
            id = id,
            userId = userInDb.id,
            // COULD use d2e here, or n2e if I store Null instead of '-'.
            OpenIdDetails(
              oidEndpoint = rs.getString("OID_ENDPOINT"),
              oidVersion = rs.getString("OID_VERSION"),
              oidRealm = rs.getString("OID_REALM"),
              oidClaimedId = rs.getString("OID_CLAIMED_ID"),
              oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
              firstName = rs.getString("i_first_name"),
              email = email,
              country = rs.getString("i_country")))
        }
      }

      assErrIf(rs.next, "DwE53IK24", "More that one matching OpenID "+
         "identity, when looking up openId: "+ anyOpenIdDetails +
         ", login: "+ loginOpt)

      Some(identityInDb) -> Some(userInDb)
    })
  }


  def createPasswordIdentityAndRole(identityNoId: PasswordIdentity, userNoId: User)
        : (PasswordIdentity, User) = {
    db.transaction { connection =>
      val user = _insertUser(siteId, userNoId)(connection)
      val identity = insertPasswordIdentity(
        otherSiteId = siteId, identityNoId.copy(userId = user.id))(connection)
      (identity, user)
    }
  }


  def loadIdtyAndUser(forLoginId: String): Option[(Identity, User)] = {
    def loginInfo = "login id "+ safed(forLoginId) +
          ", tenant "+ safed(siteId)

    _loadIdtysAndUsers(forLoginIds = forLoginId::Nil) match {
      case (List(i: Identity), List(u: User)) => Some(i, u)
      case (List(i: Identity), Nil) => assErr(
        "DwE6349krq20", "Found no user for "+ loginInfo +
            ", with identity "+ safed(i.id))
      case (Nil, Nil) =>
        // The webapp should never try to load non existing identities?
        // (The login id was once fetched from the database.
        // It is sent to the client in a signed cookie so it cannot be
        // tampered with.) Identities cannot be deleted!
        // This might happen however, if a server is restarted and switches
        // over to another database, where the login id does not exist, and
        // the server continues using the same signed cookie salt.
        // 1. The server could do that if a failover happens to a standby
        // database, and a few transactions were lost when the master died?!
        // 2. This could also happen during testing, if I manually
        // delete the login.
        // Let the caller deal with the error (it'll probably silently
        // create a new session or show an error message).
        None
      case (is, us) =>
        // There should be exactly one identity per login, and at most
        // one user per identity.
        assErr("DwE42RxkW1", "Found "+ is.length +" identities and "+
              us.length +" users for "+ loginInfo)
    }
  }


  /**
   * Looks up people by page id or login id. Does not load all authentication details.
   */
  // SHOULD reuse a `connection: js.Connection` but doesnt
  private def _loadIdtysAndUsers(onPageWithId: String = null,
                         forLoginIds: List[String] = null
                            ): Pair[List[Identity], List[User]] = {
    // Load users. First find all relevant identities, by joining
    // DW1_PAGE_ACTIONS and _LOGINS. Then all user ids, by joining
    // the result with _IDS_SIMPLE and _IDS_OPENID. Then load the users.

    require((onPageWithId ne null) ^ (forLoginIds ne null))

    val (selectLoginIds, args) = (onPageWithId, forLoginIds) match {
      case (null, Nil) => return (Nil, Nil)
      // (Need to specify tenant id here, and when selecting from DW1_USERS,
      // because there's no foreign key from DW1_LOGINS to DW1_IDS_<type>.)
      case (null, loginIds) => ("""
          select ID_SNO, ID_TYPE
              from DW1_LOGINS
              where  TENANT = ? and SNO in ("""+ makeInListFor(loginIds) +""")
          """, siteId :: loginIds)
      case (pageId, null) => ("""
          select distinct l.ID_SNO, l.ID_TYPE
              from DW1_PAGE_ACTIONS a, DW1_LOGINS l
              where a.PAGE_ID = ? and a.TENANT = ?
                and a.LOGIN = l.SNO and a.TENANT = l.TENANT
          """, List(pageId, siteId))
      case (x, y) => assErr(
        "DwE33Zb7", s"Mismatch: (${classNameOf(x)}, ${classNameOf(y)})")
    }

    // Load identities and users. Details: First find identities of all types
    // by joining logins with each identity table, and then taking the union
    // of all these joins. Use generic column names (since each identity
    // table has identity provider specific column names).
    // Then join all the identities found with DW1_USERS.
    // Note: There are identities with no matching users (IdentitySimple),
    // so do a left outer join.
    // Note: There might be > 1 identity per user (if a user has merged
    // e.g. her Twitter and Facebook identities to one single user account).
    // So each user might be returned > 1 times, i.e. once per identity.
    // This wastes some bandwidth, but I guess it's better than doing a
    // separate query to fetch all relevant users exactly once -- that
    // additional roundtrip to the database would probably be more expensive;
    // I guess fairly few users will merge their identities.
    db.queryAtnms("""
        with logins as ("""+ selectLoginIds +"""),
        identities as (
            -- Simple identities
            select ID_TYPE, g.ID I_ID, '' I_USR,
                   g.NAME I_NAME, g.EMAIL_ADDR I_EMAIL,
                   e.EMAIL_NOTFS I_EMAIL_NOTFS,
                   g.LOCATION I_WHERE, g.URL I_WEBSITE
            from logins, DW1_GUESTS g
              left join DW1_IDS_SIMPLE_EMAIL e
              on g.EMAIL_ADDR = e.EMAIL and e.VERSION = 'C'
            where g.SITE_ID = ?
              and g.ID = logins.ID_SNO
              and logins.ID_TYPE = 'Simple'
            union
            -- OpenID
            select ID_TYPE, oi.SNO I_ID, oi.USR,
                   oi.FIRST_NAME I_NAME, oi.EMAIL I_EMAIL,
                   null as I_EMAIL_NOTFS,
                   oi.COUNTRY I_WHERE, cast('' as varchar(100)) I_WEBSITE
            from DW1_IDS_OPENID oi, logins
            where oi.SNO = logins.ID_SNO and logins.ID_TYPE = 'OpenID'
              and oi.TENANT = ?
            -- union
            -- Twitter tables
            -- Facebook tables
            -- Email identities (skip for now, only used when unsubscribing)
            )
        select i.ID_TYPE, i.I_ID,
            i.I_NAME, i.I_EMAIL,
            case
              -- Can only be non-null for IdentitySimple.
              when i.I_EMAIL_NOTFS is not null then i.I_EMAIL_NOTFS
              else u.EMAIL_NOTFS end  -- might be null
              u_email_notfs,
            i.I_WHERE, i.I_WEBSITE,
            u.SNO u_id,
            u.DISPLAY_NAME u_disp_name,
            u.EMAIL u_email,
            u.COUNTRY u_country,
            u.WEBSITE u_website,
            u.SUPERADMIN u_superadmin,
            u.IS_OWNER u_is_owner
        from identities i left join DW1_USERS u on
              u.SNO = i.I_USR and
              u.TENANT = ?
        """, args ::: List(siteId, siteId, siteId), rs => {
      var usersById = mut.HashMap[String, User]()
      var identities = List[Identity]()
      while (rs.next) {
        val idId = rs.getString("I_ID")
        var userId = rs.getLong("u_id").toString  // 0 if null
        var user: Option[User] = None
        assErrIf3(idId isEmpty, "DwE392Qvc89")

        // Warning: Some dupl code in _loadIdtyDetailsAndUser:
        // COULD break out construction of Identity to reusable
        // functions.

        identities ::= (rs.getString("ID_TYPE") match {
          case "Simple" =>
            userId = _dummyUserIdFor(idId)
            val emailPrefs = _toEmailNotfs(rs.getString("u_email_notfs"))
            val i = IdentitySimple(
                id = idId,
                userId = userId,
                name = d2e(rs.getString("I_NAME")),
                email = d2e(rs.getString("I_EMAIL")),
                location = d2e(rs.getString("I_WHERE")),
                website = d2e(rs.getString("I_WEBSITE")))
            user = Some(_dummyUserFor(i, emailNotfPrefs = emailPrefs))
            i
          case "OpenID" =>
            assErrIf3(userId isEmpty, "DwE9V86kr8")
            IdentityOpenId(
                id = idId,
                userId = userId,
                // These uninteresting OpenID fields were never loaded.
                // COULD place them in an Option[OpenIdInfo]?
                OpenIdDetails(
                  oidEndpoint = "?",
                  oidVersion = "?",
                  oidRealm = "?",
                  oidClaimedId = "?",
                  oidOpLocalId = "?",
                  firstName = n2e(rs.getString("I_NAME")),
                  email = n2e(rs.getString("I_EMAIL")),
                  country = n2e(rs.getString("I_WHERE"))))
        })

        if (user isEmpty)
          user = Some(_User(rs))

        if (!usersById.contains(userId)) usersById(userId) = user.get
      }
      (identities, usersById.values.toList)  // silly to throw away hash map
    })
  }


  /**
   * Loads many users, for example, to send to the Admin app so user
   * names can be listed with comments and edits.
   *
   * Also loads Login:s and IdentityOpenId:s, so each action can be
   * associated with the relevant user.
   */
  private[rdb] def _loadUsersWhoDid(actions: List[PostActionDtoOld])
        (implicit connection: js.Connection): People = {
    val loginIds: List[String] = actions map (_.loginId)
    val logins = _loadLogins(byLoginIds = loginIds)
    val (idtys, users) = _loadIdtysAndUsers(forLoginIds = loginIds)
    People(logins, idtys, users)
  }


  private[rdb] def _loadUser(userId: String): Option[User] = loadUsers(userId::Nil).headOption


  private[rdb] def loadUsers(userIds: List[String]): List[User] = {
    val usersBySiteAndId =  // SHOULD specify consumers
      systemDaoSpi.loadUsers(Map(siteId -> userIds))
    usersBySiteAndId.values.toList
  }


  override def listUsers(userQuery: UserQuery): Seq[(User, Seq[String])] = {
    db.withConnection(implicit connection => {
      listUsersImp(userQuery)
    })
  }


  /**
   * Looks up people by user details. List details and all endpoints via which
   * the user has connected.
   */
  private def listUsersImp(userQuery: UserQuery)(implicit connection: js.Connection)
        : Seq[(User, Seq[String])] = {

    // For now, simply list all users (guests union roles).
    val query =
      i"""select
        '-' || g.ID as u_id,
        g.NAME u_disp_name,
        g.EMAIL_ADDR u_email,
        e.EMAIL_NOTFS u_email_notfs,
        g.LOCATION u_country,
        g.URL u_website,
        'F' u_superadmin,
        'F' u_is_owner,
        'Guest' i_endpoint
      from DW1_GUESTS g left join DW1_IDS_SIMPLE_EMAIL e
      on g.EMAIL_ADDR = e.EMAIL and g.SITE_ID = e.TENANT
      where g.SITE_ID = ?
      union
      select
        ${_UserSelectListItems},
        i.OID_ENDPOINT i_endpoint
      from
        DW1_USERS u left join DW1_IDS_OPENID i
      on
        u.SNO = i.USR and u.TENANT = i.TENANT
      where
        u.TENANT = ?
      """

    val values = List(siteId, siteId)
    val result: mut.Map[String, (User, List[String])] = mut.Map.empty

    db.queryAtnms(query, values, rs => {
      while (rs.next) {
        val endpoint = rs.getString("i_endpoint")
        val user = _User(rs)
        result.get(user.id) match {
          case Some((user, endpoints)) =>
            result(user.id) = (user, endpoint :: endpoints)
          case None =>
            result(user.id) = (user, endpoint :: Nil)
        }
      }
    })

    result.values.toList
  }


  override def loadPage(pageGuid: String, tenantId: Option[String] = None)
        : Option[PageParts] =
    _loadPageAnyTenant(
      tenantId = tenantId getOrElse this.siteId,
      pageId = pageGuid)


  private def _loadPageAnyTenant(tenantId: String, pageId: String)
        : Option[PageParts] = {
    /*
    db.transaction { implicit connection =>
      // BUG: There might be a NPE / None.get because of phantom reads.
      // Prevent phantom reads from DW1_ACTIONS. (E.g. rating tags are read
      // from DW1_RATINGS before _ACTIONS is considered, and another session
      // might insert a row into _ACTIONS just after _RATINGS was queried.)
      connection.setTransactionIsolation(
        Connection.TRANSACTION_SERIALIZABLE)
      */

    // COULD reuse connection throughout function, make it implicit in arg.
    var logins: List[Login] =
      db.withConnection { _loadLogins(onPageGuid = pageId)(_) }

    // Load identities and users.
    val (identities, users) = _loadIdtysAndUsers(onPageWithId = pageId)

    // Load rating tags.
    val ratingTags: mut.HashMap[ActionId, List[String]] = db.queryAtnms("""
        select a.PAID, r.TAG from DW1_PAGE_ACTIONS a, DW1_PAGE_RATINGS r
        where a.TYPE = 'Rating' and a.TENANT = ? and a.PAGE_ID = ?
          and r.TENANT = a.TENANT and r.PAGE_ID = a.PAGE_ID and r.PAID = a.PAID
        order by a.PAID
        """,
      List(tenantId, pageId), rs => {
        val map = mut.HashMap[ActionId, List[String]]()
        var tags = List[String]()
        var curPaid = PageParts.NoId  // current page action id

        while (rs.next) {
          val paid = rs.getInt("PAID")
          val tag = rs.getString("TAG")
          if (curPaid == PageParts.NoId) curPaid = paid
          if (paid == curPaid) tags ::= tag
          else {
            // All tags found for the rating with _ACTIONS.PAID = curPaid.
            map(curPaid) = tags
            tags = tag::Nil
            curPaid = paid
          }
        }
        if (tags.nonEmpty)
          map(curPaid) = tags

        map
      })

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
        val action = _Action(rs, ratingTags)
        actions ::= action  // this reverses above `order by TIME desc'
      }

      Some(PageParts.fromActions(
          pageId, People(logins, identities, users), actions))
    })
  }


  def loadPageBodiesTitles(pageIds: Seq[String]): Map[String, PageParts] = {

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
              'Post', 'Edit', 'EditApp', 'Rjct', 'Aprv', 'DelPost', 'DelTree'))"""

    val values = siteId :: pageIds.toList

    val (
      people: People,
      pagesById: mut.Map[String, PageParts],
      pageIdsAndActions: List[(String, PostActionDtoOld)]) =
        _loadPeoplePagesActionsNoRatingTags(sql, values)

    Map[String, PageParts](pagesById.toList: _*)
  }


  def loadCachedPostsById(postIdsByPageId: Map[PageId, Seq[PostId]])(connection: js.Connection)
        : Map[PageId, PageParts] = {
    /* val postStatesByPageId: Map[PageId, Seq[PostState]] =
      loadPostStatesById(postIdsByPageId)(connection)
      */
    unimplemented
  }


  def loadPostsRecentlyActive(limit: Int, offset: Int): (List[Post], People) = {
    db.withConnection { implicit connection =>
      var statess: List[List[(String, PostState)]] = Nil
      if (statess.length < limit) statess ::= loadPostStatesPendingFlags(limit, offset)
      if (statess.length < limit) statess ::= loadPostStatesPendingApproval(limit, offset)
      if (statess.length < limit) statess ::= loadPostStatesWithSuggestions(limit, offset)
      if (statess.length < limit) statess ::= loadPostStatesHandled(limit, offset)

      val states = statess.flatten
      val userIds = states.map(_._2.creationPostActionDto.userId)

      val people = People(users = loadUsers(userIds))

      val statesByPageId: Map[String, List[PostState]] =
        states.groupBy(_._1).mapValues(_.map(_._2))

      val pagesById: Map[String, PageParts] = for ((pageId, states) <- statesByPageId) yield {
        pageId -> PageParts(pageId, people, postStates = states)
      }

      val posts = pagesById.values.flatMap(_.getAllPosts).toList

      // Sort posts so flags appear first, then new posts pending approval, then
      // pending edits, then edit suggestions, then old posts with nothing new.
      // The sort order must match the indexes used when loading posts — see the
      // `loadPostStates...` invokations above.

      def sortFn(a: Post, b: Post): Boolean = {
        if (a.numPendingFlags > b.numPendingFlags) return true
        if (a.numPendingFlags < b.numPendingFlags) return false

        val aIsPendingReview = !a.currentVersionPermReviewed
        val bIsPendingReview = !b.currentVersionPermReviewed

        if (aIsPendingReview && !bIsPendingReview) return true
        if (!aIsPendingReview && bIsPendingReview) return false
        if (aIsPendingReview && bIsPendingReview)
          return a.lastActedUponAt.getTime > b.lastActedUponAt.getTime

        if (a.numPendingEditSuggestions > 0 && b.numPendingEditSuggestions == 0)
          return true
        if (a.numPendingEditSuggestions == 0 && b.numPendingEditSuggestions > 0)
          return false

        a.lastActedUponAt.getTime > b.lastActedUponAt.getTime
      }

      val postsSorted = posts sortWith sortFn

      (postsSorted, people)
    }
  }


  def loadRecentActionExcerpts(fromIp: Option[String],
        byIdentity: Option[String],
        pathRanges: PathRanges, limit: Int): (Seq[PostActionOld], People) = {

    def buildByPersonQuery(fromIp: Option[String],
          byIdentity: Option[String], limit: Int) = {

      val (loginIdsWhereSql, loginIdsWhereValues) =
        if (fromIp isDefined)
          ("l.LOGIN_IP = ? and l.TENANT = ?", List(fromIp.get, siteId))
        else
          ("l.ID_SNO = ? and l.ID_TYPE = 'OpenID' and l.TENANT = ?",
             List(byIdentity.get, siteId))

      // For now, don't select posts only. We're probably interested in
      // all actions by this user, e.g also his/her ratings, to find
      // out if s/he is astroturfing. (See this function's docs in class Dao.)
      val sql = """
           select a.TENANT, a.PAGE_ID, a.PAID, a.POST_ID
           from DW1_LOGINS l inner join DW1_PAGE_ACTIONS a
           on l.TENANT = a.TENANT and l.SNO = a.LOGIN
           where """+ loginIdsWhereSql +"""
           order by l.LOGIN_TIME desc
           limit """+ limit

      // Load things that concerns the selected actions only — not
      // everything that concerns the related posts.
      val whereClause = """
        -- Load actions by login id / folder / page id.
        a.PAID = actionIds.PAID
        -- Load actions that affected [an action by login id]
        -- (e.g. edits, flags, approvals).
          or (
          a.TYPE <> 'Rating' and -- skip ratings
          a.POST_ID = actionIds.POST_ID)"""

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

    val lookupByPerson = fromIp.isDefined || byIdentity.isDefined
    val lookupByPaths = pathRanges != PathRanges.Anywhere

    // By IP or identity id lookup cannot be combined with by path lookup.
    require(!lookupByPaths || !lookupByPerson)
    // Cannot lookup both by IP and by identity id.
    if (lookupByPerson) require(fromIp.isDefined ^ byIdentity.isDefined)
    require(0 <= limit)

    val (selectActionIds, values, postIdWhereClause) =
      if (lookupByPerson) buildByPersonQuery(fromIp, byIdentity, limit)
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
      pagesById: mut.Map[String, PageParts],
      pageIdsAndActions: List[(String, PostActionDtoOld)]) =
        _loadPeoplePagesActionsNoRatingTags(sql, values)

    val pageIdsAndActionsDescTime =
      pageIdsAndActions sortBy { case (_, action) => - action.ctime.getTime }

    def debugDetails = "fromIp: "+ fromIp +", byIdentity: "+ byIdentity +
       ", pathRanges: "+ pathRanges

    val smartActions = pageIdsAndActionsDescTime map { case (pageId, action) =>
      val page = pagesById.get(pageId).getOrElse(assErr(
        "DwE9031211", "Page "+ pageId +" missing when loading recent actions, "+
        debugDetails))
      PostActionOld(page, action)
    }

    (smartActions, people)
  }


  /**
   * Loads People, Pages (Debate:s) and Actions given an SQL statement
   * that selects:
   *   DW1_PAGE_ACTIONS.PAGE_ID and
   *   RdbUtil.ActionSelectListItems.
   */
  private def _loadPeoplePagesActionsNoRatingTags(
        sql: String, values: List[AnyRef])
        : (People, mut.Map[String, PageParts], List[(String, PostActionDtoOld)]) = {
    val pagesById = mut.Map[String, PageParts]()
    var pageIdsAndActions = List[(String, PostActionDtoOld)]()

    val people = db.withConnection { implicit connection =>
      db.query(sql, values, rs => {
        while (rs.next) {
          val pageId = rs.getString("PAGE_ID")
          // Skip rating tags, for now: (as stated in the docs in Dao.scala)
          val action = _Action(rs, ratingTags = Map.empty.withDefaultValue(Nil))
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


  // ? Should replace this function with a call to CreateSiteSystemDaoMixin.createSiteImpl ?
  // And do everything in the same transaction!
  def createWebsite(name: String, address: String, ownerIp: String,
        ownerLoginId: String, ownerIdentity: Identity, ownerRole: User)
        : Option[(Tenant, User)] = {
    try {
      db.transaction { implicit connection =>
        val websiteCount = _countWebsites(createdFromIp = ownerIp)
        if (websiteCount >= MaxWebsitesPerIp)
          throw TooManySitesCreatedException(ownerIp)

        val newTenantNoId = Tenant(id = "?", name = name,
           creatorIp = ownerIp, creatorTenantId = siteId,
           creatorLoginId = ownerLoginId, creatorRoleId = ownerRole.id,
           hosts = Nil)
        val newTenant = _createTenant(newTenantNoId)
        val newHost = TenantHost(address, TenantHost.RoleCanonical, TenantHost.HttpsNone)
        val newHostCount = systemDaoSpi.insertTenantHost(newTenant.id, newHost)(connection)
        assErrIf(newHostCount != 1, "DwE09KRF3")
        val ownerRoleAtNewWebsite = _insertUser(newTenant.id,
          ownerRole.copy(id = "?",
            isAdmin = true, isOwner = true))
        val ownerIdtyAtNewWebsite = insertIdentity(
          ownerIdentity, userId = ownerRoleAtNewWebsite.id, otherSiteId = newTenant.id)(connection)
        Some((newTenant.copy(hosts = List(newHost)), ownerRoleAtNewWebsite))
      }
    }
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex)) throw ex
        None
    }
  }


  private def _countWebsites(createdFromIp: String)
        (implicit connection: js.Connection): Int = {
    db.query("""
        select count(*) WEBSITE_COUNT from DW1_TENANTS where CREATOR_IP = ?
        """, createdFromIp::Nil, rs => {
      rs.next()
      val websiteCount = rs.getInt("WEBSITE_COUNT")
      websiteCount
    })
  }


  private def _createTenant(tenantNoId: Tenant)
        (implicit connection: js.Connection): Tenant = {
    assErrIf(tenantNoId.id != "?", "DwE91KB2")
    val tenant = tenantNoId.copy(
      id = db.nextSeqNo("DW1_TENANTS_ID").toString)
    db.update("""
        insert into DW1_TENANTS (
          ID, NAME, CREATOR_IP,
          CREATOR_TENANT_ID, CREATOR_LOGIN_ID, CREATOR_ROLE_ID)
        values (?, ?, ?, ?, ?, ?)
              """,
      List[AnyRef](tenant.id, tenant.name, tenant.creatorIp,
        tenant.creatorTenantId, tenant.creatorLoginId, tenant.creatorRoleId))
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
    sortBy: PageSortOrder,
    limit: Int,
    offset: Int): Seq[PagePathAndMeta] = {

    require(1 <= limit)
    require(offset == 0)  // for now
    require(pageRanges.pageIds isEmpty) // for now

    val statusesToInclStr =
      includeStatuses.map(_toFlag).mkString("'", "','", "'")
    if (statusesToInclStr isEmpty)
      return Nil

    val orderByStr = sortBy match {
      case PageSortOrder.ByPath =>
        " order by t.PARENT_FOLDER, t.SHOW_ID, t.PAGE_SLUG"
      case PageSortOrder.ByPublTime =>
        // For now: (CACHED_PUBL_TIME not implemented)
        " order by t.CDATI desc"
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


  def listChildPages(parentPageId: String, sortBy: PageSortOrder,
        limit: Int, offset: Int, filterPageRole: Option[PageRole] = None)
        : Seq[PagePathAndMeta] = {

    require(1 <= limit)
    require(offset == 0)  // for now

    val orderByStr = sortBy match {
      case PageSortOrder.ByPublTime => " order by g.PUBL_DATI desc"
      case _ => unimplemented("sorting by anything but page publ dati")
    }

    var values: List[AnyRef] = siteId :: parentPageId :: Nil

    val pageRoleTestAnd = filterPageRole match {
      case Some(pageRole) =>
        illArgIf(pageRole == PageRole.Generic, "DwE20kIR8")
        values ::= _pageRoleToSql(pageRole)
        "g.PAGE_ROLE = ? and"
      case None => ""
    }

    val sql = s"""
        select t.PARENT_FOLDER,
            t.PAGE_ID,
            t.SHOW_ID,
            t.PAGE_SLUG,
            ${_PageMetaSelectListItems}
        from DW1_PAGES g left join DW1_PAGE_PATHS t
          on g.TENANT = t.TENANT and g.GUID = t.PAGE_ID
          and t.CANONICAL = 'C'
        where $pageRoleTestAnd g.TENANT = ? and g.PARENT_PAGE_ID = ?
        """+ orderByStr +"""
        limit """+ limit

    var items = List[PagePathAndMeta]()

    db.withConnection { implicit connection =>
      val parentsAncestors = loadAncestorIdsParentFirstImpl(parentPageId)(connection)
      val ancestorIds = parentPageId :: parentsAncestors
      db.query(sql, values, rs => {
        while (rs.next) {
          val pagePath = _PagePath(rs, siteId)
          val pageMeta = _PageMeta(rs, pagePath.pageId.get)
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
      val p = reqInfo.pagePath.path
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
      editPage = isWiki,
      // Authenticated users can edit others' comments.
      //  — no, disable this for now, seems too dangerous.
      //    Instead I should perhaps have the AutoApprover check the user's
      //    past actions, and only sometimes automatically approve edits.
      // (In the future, the reputation system (not implemented) will make
      // them lose this ability should they misuse it.)
      editAnyReply = isWiki, // || reqInfo.user.map(_.isAuthenticated) == Some(true)
      pinReplies = isWiki || isPageAuthor)
  }


  def saveNotfs(notfs: Seq[NotfOfPageAction]) {
    db.transaction { implicit connection =>
      saveNotfsImpl(notfs)(connection)
    }
  }


  private def saveNotfsImpl(notfs: Seq[NotfOfPageAction])(implicit connection: js.Connection) {
      val valss: List[List[AnyRef]] = for (notf <- notfs.toList) yield List(
        siteId, notf.ctime, notf.pageId, notf.pageTitle take 80,
        notf.recipientIdtySmplId.orNullVarchar,
        notf.recipientRoleId.orNullVarchar,
        notf.eventType.toString,
        notf.eventActionId.asAnyRef,
        notf.triggerActionId.asAnyRef,
        notf.recipientActionId.asAnyRef,
        notf.recipientUserDispName, notf.eventUserDispName,
        notf.triggerUserDispName.orNullVarchar,
        if (notf.emailPending) "P" else NullVarchar,
        notf.debug.orNullVarchar)

      db.batchUpdate("""
        insert into DW1_NOTFS_PAGE_ACTIONS(
            TENANT, CTIME, PAGE_ID, PAGE_TITLE,
            RCPT_ID_SIMPLE, RCPT_ROLE_ID,
            EVENT_TYPE, EVENT_PGA, TARGET_PGA, RCPT_PGA,
            RCPT_USER_DISP_NAME, EVENT_USER_DISP_NAME, TARGET_USER_DISP_NAME,
            EMAIL_STATUS, DEBUG)
          values (
            ?, ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?)
        """, valss)
  }


  private def _connectNotfsToEmail(
        notfs: Seq[NotfOfPageAction], emailId: Option[String],
        debug: Option[String])
        (implicit connection: js.Connection) {

    val valss: List[List[AnyRef]] =
      for (notf <- notfs.toList) yield List(
         emailId.orNullVarchar, debug.orNullVarchar,
         siteId, notf.pageId, notf.eventActionId.asAnyRef,
         notf.recipientActionId.asAnyRef)

    db.batchUpdate("""
      update DW1_NOTFS_PAGE_ACTIONS
      set MTIME = now(), EMAIL_STATUS = null, EMAIL_SENT = ?, DEBUG = ?
      where
        TENANT = ? and PAGE_ID = ? and EVENT_PGA = ? and RCPT_PGA = ?
      """, valss)
  }


  def loadNotfsForRole(userId: String): Seq[NotfOfPageAction] = {
    val numToLoad = 50 // for now
    val notfsToMail = systemDaoSpi.loadNotfsImpl(   // SHOULD specify consumers
       numToLoad, Some(siteId), userIdOpt = Some(userId))
    // All loaded notifications are to userId only.
    notfsToMail.notfsByTenant(siteId)
  }


  def loadNotfByEmailId(emailId: String): Option[NotfOfPageAction] = {
    val notfsToMail =   // SHOULD specify consumers
       systemDaoSpi.loadNotfsImpl(1, Some(siteId), emailIdOpt = Some(emailId))
    val notfs = notfsToMail.notfsByTenant(siteId)
    assert(notfs.length <= 1)
    notfs.headOption
  }


  def skipEmailForNotfs(notfs: Seq[NotfOfPageAction], debug: String) {
    db.transaction { implicit connection =>
      _connectNotfsToEmail(notfs, emailId = None, debug = Some(debug))
    }
  }


  def saveUnsentEmailConnectToNotfs(email: Email,
        notfs: Seq[NotfOfPageAction]) {
    db.transaction { implicit connection =>
      _saveUnsentEmail(email)
      _connectNotfsToEmail(notfs, Some(email.id), debug = None)
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
      d2ts(email.createdAt), email.subject, email.bodyHtmlText)

    db.update("""
      insert into DW1_EMAILS_OUT(
        TENANT, ID, TYPE, SENT_TO, CREATED_AT, SUBJECT, BODY_HTML)
      values (
        ?, ?, ?, ?, ?, ?, ?)
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
      select TYPE, SENT_TO, SENT_ON, CREATED_AT, SUBJECT,
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

        val email = Email(
           id = emailId,
           tyype = parseEmailType(rs.getString("TYPE")),
           sentTo = rs.getString("SENT_TO"),
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


  def configRole(loginId: String, ctime: ju.Date, roleId: String,
        emailNotfPrefs: Option[EmailNotfPrefs], isAdmin: Option[Boolean],
        isOwner: Option[Boolean]) {
    // Currently auditing not implemented for the roles/users table,
    // so loginId and ctime aren't used.
    require(!roleId.startsWith("-") && !roleId.startsWith("?"))

    var changes = StringBuilder.newBuilder
    var newValues: List[AnyRef] = Nil

    emailNotfPrefs foreach { prefs =>
      // Don't overwrite notifications-'F'orbidden-forever flag.
      changes ++= """EMAIL_NOTFS = case
          when EMAIL_NOTFS is null or EMAIL_NOTFS <> 'F' then ?
          else EMAIL_NOTFS
        end"""
      newValues ::= _toFlag(prefs)
    }

    isAdmin foreach { isAdmin =>
      if (changes.nonEmpty) changes ++= ", "
      changes ++= "SUPERADMIN = ?"
      newValues ::= (if (isAdmin) "T" else NullVarchar)
    }

    isOwner foreach { isOwner =>
      if (changes.nonEmpty) changes ++= ", "
      changes ++= "IS_OWNER = ?"
      newValues ::= (if (isOwner) "T" else NullVarchar)
    }

    if (newValues.isEmpty)
      return

    db.transaction { implicit connection =>
      val sql = s"update DW1_USERS set $changes where TENANT = ? and SNO = ?"
      db.update(sql, newValues.reverse ::: List(siteId, roleId))
    }
  }


  def configIdtySimple(loginId: String, ctime: ju.Date,
                       emailAddr: String, emailNotfPrefs: EmailNotfPrefs) {
    db.transaction { implicit connection =>
      // Mark the current row as 'O' (old) -- unless EMAIL_NOTFS is 'F'
      // (Forbidden Forever). Then leave it as is, and let the insert
      // below fail.
      // COULD check # rows updated? No, there might be no rows to update.
      db.update("""
          update DW1_IDS_SIMPLE_EMAIL
          set VERSION = 'O' -- old
          where TENANT = ? and EMAIL = ? and VERSION = 'C'
            and EMAIL_NOTFS != 'F'
          """,
          List(siteId, emailAddr))

      // Create a new row with the desired email notification setting.
      // Or, for now, fail and throw some SQLException if EMAIL_NOTFS is 'F'
      // for this `emailAddr' -- since there'll be a primary key violation,
      // see the update statement above.
      db.update("""
          insert into DW1_IDS_SIMPLE_EMAIL (
              TENANT, LOGIN, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, ?, 'C', ?, ?)
          """,
          List(siteId, loginId, d2ts(ctime), emailAddr,
              _toFlag(emailNotfPrefs)))
    }
  }


  def lookupPagePath(pageId: String): Option[PagePath] =
    lookupPagePathImpl(pageId)(null)


  def lookupPagePathImpl(pageId: String)(implicit connection: js.Connection)
        : Option[PagePath] =
    lookupPagePathsImpl(pageId, loadRedirects = false).headOption


  def lookupPagePathAndRedirects(pageId: String): List[PagePath] =
    lookupPagePathsImpl(pageId, loadRedirects = true)(null)


  private def lookupPagePathsImpl(pageId: String, loadRedirects: Boolean)
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

    var values = List[AnyRef]()

    val insertIntoColumnsSql = """
      insert into DW1_PAGES (
         SNO, TENANT, GUID, PAGE_ROLE, PARENT_PAGE_ID,
         CDATI, MDATI, PUBL_DATI)
      """

    val valuesSql =
      "nextval('DW1_PAGES_SNO'), ?, ?, ?, ?, ?, ?, ?"

    // If there is no parent page, use an `insert ... values (...)` statement.
    // Otherwise, use an `insert ... select ... from ...` and verify that the
    // parent page has the appropriate page role.
    val sql = page.parentPageId match {
      case None =>
        s"$insertIntoColumnsSql values ($valuesSql)"

      case Some(parentPageId) =>
        // Add `from ... where ...` values.
        values :::= List(
          page.tenantId,
          parentPageId,
          _pageRoleToSql(page.role.parentRole getOrElse {
            runErr("DwE77DK1", s"Page role ${page.role} cannot have any parent page")
          }))

        // (I think the below `where exists` results in race conditions, if
        // PAGE_ROLE can ever be changed. It is never changed though. Consider reading
        // this:  http://www.postgresql.org/docs/9.2/static/transaction-iso.html
        //          #XACT-READ-COMMITTED )
        s"""
          $insertIntoColumnsSql
          select $valuesSql
          from DW1_PAGES p
            where p.TENANT = ?
              and p.GUID = ?
              and p.PAGE_ROLE = ?
          """
    }

    values :::= List[AnyRef](page.tenantId, page.id,
      _pageRoleToSql(page.role), page.parentPageId.orNullVarchar,
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
            s" to this page: ${pagePath.path}, id `${pagePath.pageId.get}'." +
            s" (Or the server needs to generate longer page ids.)")
        }
        throw ex
    }
  }


  private def updateParentPageChildCount(parentId: String, change: Int)
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


  private def moveRenamePageImpl(pageId: String,
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

    def changeExistingPathsToRedirects(pageId: String) {
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


  override def savePageActions[T <: PostActionDtoOld](
        page: PageNoPath, actions: List[T]): (PageNoPath, List[T]) = {
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


  private def savePageActionsImpl[T <: PostActionDtoOld](
        page: PageNoPath, actions: List[T])(
        implicit conn: js.Connection):  (PageNoPath, List[T]) = {

    // Save new actions.
    val actionsWithIds = insertActions(page.id, actions)

    // Notify users whose posts were affected (e.g. if someone got a new reply).
    val notfs = NotfGenerator(page.parts, actionsWithIds).generateNotfs
    saveNotfsImpl(notfs)(conn)

    // Update cached post states (e.g. the current text of a comment).
    val newParts = page.parts ++ actionsWithIds
    val postIds = actionsWithIds.map(_.postId).distinct
    val posts =
      for (postId <- postIds)
      yield newParts.getPost(postId) getOrDie "DwE70Bf8"

    // Weird comment: (we already have the PostActionDto's here!?)
    // If a rating implies nearby posts are to be considered read,
    // we need the actual PostActionDto's here, not only post ids — so we
    // can update the read counts of all affected posts.
    posts foreach { post =>
      insertUpdatePost(post)(conn)
    }


    // Update cached page meta (e.g. the page title).
    val newMeta = PageMeta.forChangedPage(page.meta, newParts)
    if (newMeta != page.meta)
      _updatePageMeta(newMeta, anyOld = Some(page.meta))

    // Index the posts for full text search as soon as possible.
    if (!daoFactory.fastStartSkipSearch)
      fullTextSearchIndexer.indexNewPostsSoon(page, posts, siteId)

    (PageNoPath(newParts, page.ancestorIdsParentFirst, newMeta), actionsWithIds)
  }


  private def insertActions[T <: PostActionDtoOld](pageId: String, actions: List[T])
        (implicit conn: js.Connection): List[T] = {
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
            LOGIN, GUEST_ID, ROLE_ID,
            TENANT, PAGE_ID, POST_ID, PAID, TIME,
            TYPE, RELPA, TEXT, LONG_VALUE, MARKUP, WHEERE,
            APPROVAL, AUTO_APPLICATION)
          values (?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?)"""

      // There's no login (or identity or user) stored for the system user,
      // so don't try to reference it via DW1_PAGE_ACTIONS.LOGIN_ID.
      val (loginIdNullForSystem, roleIdNullForSystem) =
        if (action.loginId == SystemUser.Login.id) {
          assErrIf(action.userId != SystemUser.User.id, "DwE57FU1")
          (NullVarchar, NullVarchar)
        }
        else (action.loginId, action.anyRoleId.orNullVarchar)

      // Keep in mind that Oracle converts "" to null.
      val commonVals: List[AnyRef] = List(
        loginIdNullForSystem,
        action.anyGuestId.orNullVarchar,
        roleIdNullForSystem,
        siteId,
        pageId,
        action.postId.asAnyRef,
        action.id.asAnyRef,
        d2ts(action.ctime))

      action match {
        case r: Rating =>
          db.update(insertIntoActions, commonVals:::List(
            "Rating", NullInt,
            NullVarchar, NullInt, NullVarchar, NullVarchar,
            NullVarchar, NullVarchar))
          db.batchUpdate("""
            insert into DW1_PAGE_RATINGS(TENANT, PAGE_ID, PAID, TAG)
            values (?, ?, ?, ?)
            """, r.tags.map(t => List(siteId, pageId, r.id.asAnyRef, t)))
        case a: EditApp =>
          db.update(insertIntoActions, commonVals:::List(
            "EditApp", a.editId.asAnyRef, e2n(a.result), NullInt,
            NullVarchar, NullVarchar, _toDbVal(a.approval), NullVarchar))
        case f: Flag =>
          db.update(insertIntoActions, commonVals:::List(
            "Flag" + f.reason,
            NullInt, e2n(f.details), NullInt, NullVarchar, NullVarchar,
            NullVarchar, NullVarchar))
        case a: PostActionDto[_] =>
          def insertSimpleValue(tyype: String) =
            db.update(insertIntoActions, commonVals:::List(
              tyype, a.postId.asAnyRef, NullVarchar, NullInt, NullVarchar, NullVarchar,
              NullVarchar, NullVarchar))

          val PAP = PostActionPayload
          a.payload match {
            case p: PAP.CreatePost =>
              db.update(insertIntoActions, commonVals:::List(
                "Post", p.parentPostId.asAnyRef, e2n(p.text), NullInt, e2n(p.markup),
                e2n(p.where), _toDbVal(p.approval), NullVarchar))
            case e: PAP.EditPost =>
              val autoAppliedDbVal = if (e.autoApplied) "A" else NullVarchar
              db.update(insertIntoActions, commonVals:::List(
                "Edit", NullInt, e2n(e.text), NullInt, e2n(e.newMarkup), NullVarchar,
                _toDbVal(e.approval), autoAppliedDbVal))
            case r: PAP.ReviewPost =>
              val tyype = r.approval.isDefined ? "Aprv" | "Rjct"
              db.update(insertIntoActions, commonVals:::List(
                tyype, NullInt, NullVarchar, NullInt, NullVarchar, NullVarchar,
                _toDbVal(r.approval), NullVarchar))
            case p: PAP.PinPostAtPosition =>
              db.update(insertIntoActions, commonVals:::List(
                "PinAtPos", NullInt, NullVarchar, p.position.asAnyRef,
                NullVarchar, NullVarchar, NullVarchar, NullVarchar))

            case PAP.CollapsePost => insertSimpleValue("CollapsePost")
            case PAP.CollapseTree => insertSimpleValue("CollapseTree")
            case PAP.CloseTree => insertSimpleValue("CloseTree")
            case PAP.DeletePost => insertSimpleValue("DelPost")
            case PAP.DeleteTree => insertSimpleValue("DelTree")
            case PAP.Undo(_) => unimplemented
            case PAP.Delete(_) => unimplemented // there's no DW1_PAGE_ACTIONS.TYPE?
          }
        case x => unimplemented(
          "Saving this: "+ classNameOf(x) +" [error DwE38rkRF]")
      }
    }

    actionsWithIds
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
        MARKUP = ?,
        WHEERE = ?,
        CREATED_AT = ?,
        LAST_ACTED_UPON_AT = ?,
        LAST_REVIEWED_AT = ?,
        LAST_AUTHLY_REVIEWED_AT = ?,
        LAST_APPROVED_AT = ?,
        LAST_APPROVAL_TYPE = ?,
        LAST_PERMANENTLY_APPROVED_AT = ?,
        LAST_MANUALLY_APPROVED_AT = ?,
        AUTHOR_ID = ?,
        LAST_EDIT_APPLIED_AT = ?,
        LAST_EDIT_REVERTED_AT = ?,
        LAST_EDITOR_ID = ?,

        PINNED_POSITION = ?,
        POST_COLLAPSED_AT = ?,
        TREE_COLLAPSED_AT = ?,
        TREE_CLOSED_AT = ?,
        POST_DELETED_AT = ?,
        TREE_DELETED_AT = ?,

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
        MARKUP,
        WHEERE,
        CREATED_AT,
        LAST_ACTED_UPON_AT,
        LAST_REVIEWED_AT,
        LAST_AUTHLY_REVIEWED_AT,
        LAST_APPROVED_AT,
        LAST_APPROVAL_TYPE,
        LAST_PERMANENTLY_APPROVED_AT,
        LAST_MANUALLY_APPROVED_AT,
        AUTHOR_ID,
        LAST_EDIT_APPLIED_AT,
        LAST_EDIT_REVERTED_AT,
        LAST_EDITOR_ID,

        PINNED_POSITION,
        POST_COLLAPSED_AT,
        TREE_COLLAPSED_AT,
        TREE_CLOSED_AT,
        POST_DELETED_AT,
        TREE_DELETED_AT,

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
         ?, ?, ?, ?, ?)"""

    val collapsed: AnyRef =
      if (post.isTreeCollapsed) "CollapseTree"
      else if (post.isPostCollapsed) "CollapsePost"
      else NullVarchar

    val anyLastEditor = post.editsAppliedDescTime.headOption.map(_.user_!)
    val anyUnapprovedTextDiff = post.unapprovedText map { unapprovedText =>
      makePatch(from = post.approvedText.getOrElse(""), to = unapprovedText)
    }

    val values = List[AnyRef](
      post.parentId.asAnyRef,
      post.markup,
      post.where.orNullVarchar,
      d2ts(post.creationDati),
      d2ts(post.lastActedUponAt),
      o2ts(post.lastReviewDati), // LAST_REVIEWED_AT
      o2ts(post.lastAuthoritativeReviewDati), // LAST_AUTHLY_REVIEWED_AT
      o2ts(post.lastApprovalDati), // LAST_APPROVED_AT
      _toDbVal(post.lastApprovalType), // LAST_APPROVAL_TYPE
      o2ts(post.lastPermanentApprovalDati), // LAST_PERMANENTLY_APPROVED_AT
      o2ts(post.lastManualApprovalDati), // LAST_MANUALLY_APPROVED_AT
      post.userId,  // AUTHOR_ID
      o2ts(post.lastEditAppliedAt), // LAST_EDIT_APPLIED_AT
      o2ts(post.lastEditRevertedAt), // LAST_EDIT_REVERTED_AT
      anyLastEditor.map(_.id).orNullVarchar, // LAST_EDITOR_ID

      post.pinnedPosition.orNullInt,
      o2ts(post.postCollapsedAt),
      o2ts(post.treeCollapsedAt),
      o2ts(post.treeClosedAt),
      o2ts(post.postDeletedAt),
      o2ts(post.treeDeletedAt),

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

  private def loadPostStatesPendingFlags(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : List[(String, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = "NUM_PENDING_FLAGS > 0",
      orderBy = Some("SITE_ID, NUM_PENDING_FLAGS desc"))


  private def loadPostStatesPendingApproval(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : List[(String, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = s"""
        NUM_PENDING_FLAGS = 0 and (
          (LAST_APPROVAL_TYPE is null or LAST_APPROVAL_TYPE = 'P') or
          NUM_EDITS_TO_REVIEW > 0 or
          NUM_COLLAPSES_TO_REVIEW > 0 or
          NUM_UNCOLLAPSES_TO_REVIEW > 0 or
          NUM_DELETES_TO_REVIEW > 0 or
          NUM_UNDELETES_TO_REVIEW > 0)
        """,
      orderBy = Some("SITE_ID, LAST_ACTED_UPON_AT desc"))


  private def loadPostStatesWithSuggestions(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : List[(String, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = s"""
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


  private def loadPostStatesHandled(
        limit: Int, offset: Int)(implicit connection: js.Connection)
        : List[(String, PostState)] =
    loadPostStatesImpl(limit, offset,
      whereTests = s"""
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
        """,
      orderBy = Some("SITE_ID, LAST_ACTED_UPON_AT desc"))


  private def loadPostStatesImpl(
      limit: Int, offset: Int, whereTests: String, orderBy: Option[String] = None)(
      implicit connection: js.Connection): List[(String, PostState)] = {

    val orderByClause = orderBy.map("order by " + _) getOrElse ""
    val sql = s"""
      select * from DW1_POSTS
      where SITE_ID = ? and $whereTests
      $orderByClause limit $limit offset $offset
      """

    val values = List(siteId)
    var result: List[(String, PostState)] = Nil

    db.query(sql, values, rs => {
      while (rs.next) {
        val pageId = rs.getString("PAGE_ID")
        result ::= (pageId, readPostState(rs))
      }
    })

    result
  }


  private def loadPostStatesById(postIdsByPageId: Map[PageId, Seq[PostId]])(
        implicit connection: js.Connection): Map[PageId, Seq[PostState]] = {
    unimplemented /*
    var values = Vector[AnyRef](siteId)

    val pagePostIdsClauseList =
      for ((pageId, postIds) <- postIdsByPageId)
      yield {
        values :+= pageId
        values ++= postIds
        val postIdClauses = postIds.map(_ => "POST_ID = ?").mkString(" or ")
        s"(PAGE_ID = ? and ($postIdClauses))"
      }

    val pagePostIdsClauseStr = pagePostIdsClauseList.mkString(" or ")

    val sql = s"""
      select * from DW1_POSTS
      where SITE_ID = ? and ($pagePostIdsClauseStr)
      order by SITE_ID, PAGE_ID
      """

    --- Wrong result type! ---
    var result: List[(PageId, PostState)] = Nil
    db.query(sql, values.toList, rs => {
      while (rs.next) {
        val pageId = rs.getString("PAGE_ID")
        result ::= (pageId, readPostState(rs))
      }
    })
    result
    */
  }


  private def readPostState(rs: js.ResultSet): PostState = {

    val anyApprovedText = Option(rs.getString("APPROVED_TEXT"))
    val anyUnapprovedTextDiff = Option(rs.getString("UNAPPROVED_TEXT_DIFF"))
    val anyUnapprovedText = anyUnapprovedTextDiff map { patchText =>
      applyPatch(patchText, to = anyApprovedText getOrElse "")
    }

    val postActionDto = PostActionDto.forNewPost(
      id = rs.getInt("POST_ID"),
      creationDati = ts2d(rs.getTimestamp("CREATED_AT")),
      loginId = "?",
      userId = rs.getString("AUTHOR_ID"),
      newIp = None, // for now
      parentPostId = rs.getInt("PARENT_POST_ID"),
      text = anyUnapprovedText getOrElse anyApprovedText.get,
      markup = rs.getString("MARKUP"),
      approval = _toAutoApproval(rs.getString("LAST_APPROVAL_TYPE")),
      where = Option(rs.getString("WHEERE")))

    new PostState(
      postActionDto,
      lastActedUponAt = ts2d(rs.getTimestamp("LAST_ACTED_UPON_AT")),
      lastReviewDati = ts2o(rs.getTimestamp("LAST_REVIEWED_AT")),
      lastAuthoritativeReviewDati = ts2o(rs.getTimestamp("LAST_AUTHLY_REVIEWED_AT")),
      lastApprovalDati = ts2o(rs.getTimestamp("LAST_APPROVED_AT")),
      lastApprovedText = anyApprovedText,
      lastPermanentApprovalDati = ts2o(rs.getTimestamp("LAST_PERMANENTLY_APPROVED_AT")),
      lastManualApprovalDati = ts2o(rs.getTimestamp("LAST_MANUALLY_APPROVED_AT")),
      lastEditAppliedAt = ts2o(rs.getTimestamp("LAST_EDIT_APPLIED_AT")),
      lastEditRevertedAt = ts2o(rs.getTimestamp("LAST_EDIT_REVERTED_AT")),
      lastEditorId = Option(rs.getString("LAST_EDITOR_ID")),
      pinnedPosition = Option(rs.getInt("PINNED_POSITION")),
      postCollapsedAt = ts2o(rs.getTimestamp("POST_COLLAPSED_AT")),
      treeCollapsedAt = ts2o(rs.getTimestamp("TREE_COLLAPSED_AT")),
      treeClosedAt = ts2o(rs.getTimestamp("TREE_CLOSED_AT")),
      postDeletedAt = ts2o(rs.getTimestamp("POST_DELETED_AT")),
      treeDeletedAt = ts2o(rs.getTimestamp("TREE_DELETED_AT")),
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



