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


/** Creates and updates users and identities.
  */
trait UserSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  private[rdb] def _insertUser(tenantId: SiteId, userNoId: User)
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


  private[rdb] def insertIdentity(identityNoId: Identity, userId: UserId, otherSiteId: SiteId)
        (connection: js.Connection): Identity = {
    identityNoId match {
      case x: IdentityOpenId =>
        insertOpenIdIdentity(otherSiteId, x.copy(id = "?", userId = userId))(connection)
      case x: PasswordIdentity =>
        insertPasswordIdentity(otherSiteId, x.copy(id = "?", userId = userId))(connection)
      case x: OpenAuthIdentity =>
        insertOpenAuthIdentity(otherSiteId, x.copy(id = "?", userId = userId))(connection)
      case x =>
        assErr(s"Don't know how to insert identity of type: ${classNameOf(x)}")
    }
  }


  private[rdb] def insertOpenIdIdentity(tenantId: SiteId, idtyNoId: Identity)
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
              e2d(details.firstName), details.email.orNullVarchar, e2d(details.country)))
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
        e2d(details.firstName), details.email.orNullVarchar, e2d(details.country),
        identity.id, siteId))
  }


  private[rdb] def insertOpenAuthIdentity(
        otherSiteId: SiteId, identityNoId: OpenAuthIdentity)
        (implicit connection: js.Connection): OpenAuthIdentity = {
    val newIdentityId = db.nextSeqNo("DW1_IDS_SNO").toString
    val identity = identityNoId.copy(id = newIdentityId)
    val sql = """
        insert into DW1_IDS_OPENID(
            SNO, TENANT, USR, USR_ORIG,
            FIRST_NAME, LAST_NAME, FULL_NAME, EMAIL, AVATAR_URL,
            AUTH_METHOD, SECURESOCIAL_PROVIDER_ID, SECURESOCIAL_USER_ID)
        values (
            ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?)"""
    val ds = identity.openAuthDetails
    val method = "OAuth" // should probably remove this column
    val values = List[AnyRef](
      identity.id, otherSiteId, identity.userId, identity.userId,
      ds.firstName.orNullVarchar, ds.lastName.orNullVarchar,
      ds.fullName.orNullVarchar, ds.email.orNullVarchar, ds.avatarUrl.orNullVarchar,
      method, ds.providerId, ds.providerKey)
    db.update(sql, values)
    identity
  }


  private[rdb] def updateOpenAuthIdentity(identity: OpenAuthIdentity)
        (implicit connection: js.Connection) {
    val sql = """
      update DW1_IDS_OPENID set
        USR = ?, AUTH_METHOD = ?,
        FIRST_NAME = ?, LAST_NAME = ?, FULL_NAME = ?, EMAIL = ?, AVATAR_URL = ?
      where SNO = ? and TENANT = ?"""
    val ds = identity.openAuthDetails
    val method = "OAuth" // should probably remove this column
    val values = List[AnyRef](
      identity.userId, method, ds.firstName.orNullVarchar, ds.lastName.orNullVarchar,
      ds.fullName.orNullVarchar, ds.email.orNullVarchar, ds.avatarUrl.orNullVarchar,
      identity.id, siteId)
    db.update(sql, values)
  }


  def loadIdtyDetailsAndUser(
        forUserId: UserId = null,
        forOpenIdDetails: OpenIdDetails = null,
        forEmailAddr: String = null): Option[(Identity, User)] = {
    db.withConnection(implicit connection => {
      _loadIdtyDetailsAndUser(
          forUserId = forUserId,
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
        forUserId: UserId = null,
        forOpenIdDetails: OpenIdDetails = null,
        forOpenAuthProfile: OpenAuthProviderIdKey = null,
        forEmailAddr: String = null)(implicit connection: js.Connection)
        : (Option[Identity], Option[User]) = {

    val anyOpenIdDetails = Option(forOpenIdDetails)
    val anyOpenAuthKey = Option(forOpenAuthProfile)

    val anyUserId: Option[UserId] =
      if (forUserId eq null) None
      else Some(forUserId)

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
            i.SECURESOCIAL_USER_ID,
            i.SECURESOCIAL_PROVIDER_ID,
            i.PASSWORD_HASH,
            i.AUTH_METHOD,
            i.FIRST_NAME i_first_name,
            i.LAST_NAME i_last_name,
            i.FULL_NAME i_full_name,
            i.EMAIL i_email,
            i.COUNTRY i_country,
            i.AVATAR_URL i_avatar_url
          from DW1_IDS_OPENID i inner join DW1_USERS u
            on i.TENANT = u.TENANT
            and i.USR = u.SNO
        """

    val (whereClause, bindVals) =
        (anyUserId, anyEmail, anyOpenIdDetails, anyOpenAuthKey) match {

      case (Some(userId: UserId), None, None, None) =>
        UNTESTED // [nologin]
        ("""where i.TENANT = ? and i.USR = ?""",
           List(siteId, userId))

      case (None, Some(email), None, None) =>
        ("""where i.TENANT = ? and i.EMAIL = ?""",
          List(siteId, email))

      case (None, None, Some(openIdDetails: OpenIdDetails), None) =>
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
               "' and i.EMAIL = ?", openIdDetails.email.getOrDie("DwE77ZS10"))
          else
            ("i.OID_CLAIMED_ID = ?", openIdDetails.oidClaimedId)
        }
        ("""where i.TENANT = ?
            and """+ claimedIdOrEmailCheck +"""
          """, List(siteId, idOrEmail))

      case (None, None, None, Some(openAuthKey: OpenAuthProviderIdKey)) =>
        val whereClause =
          """where i.TENANT = ?
               and i.SECURESOCIAL_PROVIDER_ID = ?
               and i.SECURESOCIAL_USER_ID = ?"""
        val values = List(siteId, openAuthKey.providerId, openAuthKey.providerKey)
        (whereClause, values)

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
      val email = Option(rs.getString("i_email"))
      val anyPasswordHash = Option(rs.getString("PASSWORD_HASH"))
      val anyClaimedOpenId = Option(rs.getString("OID_CLAIMED_ID"))
      val anyOpenAuthProviderId = Option(rs.getString("SECURESOCIAL_PROVIDER_ID"))

      val identityInDb = {
        if (anyPasswordHash.nonEmpty) {
          PasswordIdentity(
            id = id,
            userId = userInDb.id,
            email = email getOrDie "DwE083FW5",
            passwordSaltHash = anyPasswordHash.get)
        }
        else if (anyClaimedOpenId.nonEmpty) {
          IdentityOpenId(
            id = id,
            userId = userInDb.id,
            // COULD use d2e here, or n2e if I store Null instead of '-'.
            OpenIdDetails(
              oidEndpoint = rs.getString("OID_ENDPOINT"),
              oidVersion = rs.getString("OID_VERSION"),
              oidRealm = rs.getString("OID_REALM"),
              oidClaimedId = anyClaimedOpenId.get,
              oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
              firstName = rs.getString("i_first_name"),
              email = email,
              country = rs.getString("i_country")))
        }
        else if (anyOpenAuthProviderId.nonEmpty) {
          OpenAuthIdentity(
            id = id,
            userId = userInDb.id,
            openAuthDetails = OpenAuthDetails(
              providerId = anyOpenAuthProviderId.get,
              providerKey = rs.getString("SECURESOCIAL_USER_ID"),
              firstName = Option(rs.getString("i_first_name")),
              lastName = Option(rs.getString("i_last_name")),
              fullName = Option(rs.getString("i_full_name")),
              email = email,
              avatarUrl = Option(rs.getString("i_avatar_url"))))
        }
        else {
          assErr("DwE77GJ2", s"Unknown identity in DW1_IDS_OPENID, site: $siteId, id: $id")
        }
      }

      assErrIf(rs.next, "DwE53IK24", "More that one matching identity, when"+
         " looking up: "+ (anyUserId, anyEmail, anyOpenIdDetails, anyOpenAuthKey))

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


  def changePassword(identity: PasswordIdentity, newPasswordSaltHash: String): Boolean = {
    db.transaction { implicit connection =>
      val sql = """
        update DW1_IDS_OPENID
        set PASSWORD_HASH = ?
        where TENANT = ? and SNO = ?
                """
      val numRowsChanged = db.update(sql, List(newPasswordSaltHash, siteId, identity.id))
      assert(numRowsChanged <= 1, "DwE87GMf0")
      numRowsChanged == 1
    }
  }


  // SHOULD reuse a `connection: js.Connection` but doesn't
  def loadUsersOnPage(pageId: PageId): List[User] = {
    val sql = s"""
      select ${_UserSelectListItems}
      from DW1_PAGE_ACTIONS a left join DW1_USERS u
        on a.TENANT = u.TENANT and a.ROLE_ID = u.SNO
        where a.TENANT = ?
          and a.PAGE_ID = ?
          and a.ROLE_ID is not null
      union
      select
        '-'||g.ID u_id,
        g.NAME u_disp_name,
        g.EMAIL_ADDR u_email,
        e.EMAIL_NOTFS u_email_notfs,
        g.LOCATION u_country,
        g.URL u_website,
        'F' u_superadmin,
        'F' u_is_owner
      from
        DW1_PAGE_ACTIONS a left join DW1_GUESTS g
          on a.TENANT = g.SITE_ID and a.GUEST_ID = g.ID
        left join DW1_IDS_SIMPLE_EMAIL e
           on g.SITE_ID = e.TENANT and g.EMAIL_ADDR = e.EMAIL
        where a.TENANT = ?
          and a.PAGE_ID = ?
          and a.GUEST_ID is not null """

    val values = List[AnyRef](siteId, pageId, siteId, pageId)
    var users: List[User] = Nil
    db.queryAtnms(sql, values, rs => {
      while (rs.next()) {
        val user = _User(rs)
        users ::= user
      }
    })
    users
  }


  /**
   * Loads many users, for example, to send to the Admin app so user
   * names can be listed with comments and edits.
   *
   * Also loads Login:s and IdentityOpenId:s, so each action can be
   * associated with the relevant user.
   */
  private[rdb] def _loadUsersWhoDid(actions: List[RawPostAction[_]])
        (implicit connection: js.Connection): People = {
    val userIds: List[UserId] = actions map (_.userIdData.userId)
    val users = loadUsersAsList(userIds)
    People(users)
  }


  def loadUser(userId: UserId): Option[User] =
    loadUsersAsList(userId::Nil).headOption


  private[rdb] def loadUsersAsList(userIds: List[UserId]): List[User] = {
    val usersBySiteAndId =  // SHOULD specify quota consumers
      systemDaoSpi.loadUsers(Map(siteId -> userIds))
    usersBySiteAndId.values.toList
  }


  private[rdb] def loadUsersAsMap(userIds: Seq[UserId]): Map[UserId, User] = {
    val usersBySiteAndId =  // SHOULD specify quota consumers
      systemDaoSpi.loadUsers(Map(siteId -> userIds.toList))
    usersBySiteAndId map { case (siteAndUserId, user) =>
      siteAndUserId._2 -> user
    }
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


  def configRole(ctime: ju.Date, roleId: RoleId,
        emailNotfPrefs: Option[EmailNotfPrefs], isAdmin: Option[Boolean],
        isOwner: Option[Boolean]) {
    // Currently auditing not implemented for the roles/users table,
    // so ctime isn't used.
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


  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs) {
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
              TENANT, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, 'C', ?, ?)
          """,
          List(siteId, d2ts(ctime), emailAddr, _toFlag(emailNotfPrefs)))
    }
  }

}



