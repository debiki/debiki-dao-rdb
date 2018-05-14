/**
 * Copyright (C) 2011-2015 Kaj Magnus Lindberg (born 1979)
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
import com.debiki.core.EmailNotfPrefs.EmailNotfPrefs
import com.debiki.core.Prelude._
import com.debiki.core.User.{LowestNonGuestId, LowestAuthenticatedUserId}
import _root_.java.{util => ju, io => jio}
import java.{sql => js}
import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import Rdb._
import RdbUtil._


/** Creates and updates users and identities.  Docs [8KFUT20].
  */
trait UserSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>

  val IsOwnerOrStaff = o"""(
     is_owner is not null and is_owner or
     is_admin is not null and is_admin or
     is_moderator is not null and is_moderator)"""


  def insertInvite(invite: Invite) {
    val statement = """
      insert into invites3(
        site_id, secret_key, email_address, created_by_id, created_at)
      values (?, ?, ?, ?, ?)
      """
    val values = List(
      siteId.asAnyRef, invite.secretKey, invite.emailAddress,
      invite.createdById.asAnyRef, invite.createdAt.asTimestamp)
    try {
      runUpdate(statement, values)
    }
    catch {
      case ex: js.SQLException =>
        // Invited-email + inviter-id is unique. [5GPJ4A0]
        if (isUniqueConstrViolation(ex) && uniqueConstrViolatedIs("dw2_invites_email__u", ex))
          throw DbDao.DuplicateUserEmail(invite.emailAddress)

        throw ex
    }
  }


  def updateInvite(invite: Invite): Boolean = {
    val statement = """
      update invites3 set
        accepted_at = ?,
        user_id = ?,
        deleted_at = ?,
        deleted_by_id = ?,
        invalidated_at = ?
      where
        site_id = ? and
        secret_key = ?
      """
    val values = List(
      invite.acceptedAt.orNullTimestamp,
      invite.userId.orNullInt,
      invite.deletedAt.orNullTimestamp,
      invite.deletedById.orNullInt,
      invite.invalidatedAt.orNullTimestamp,
      siteId.asAnyRef,
      invite.secretKey)
    runUpdateSingleRow(statement, values)
  }


  def forgetInviteEmailSentToAddress(userId: UserId, replaceWithAddr: String) {
    TESTS_MISSING
    val statement = """
      update invites3 set email_address = ?
      where
        site_id = ? and
        user_id = ?
      """
    val values = List(replaceWithAddr, siteId.asAnyRef, userId.asAnyRef)
    runUpdate(statement, values)
  }


  def loadInvite(secretKey: String): Option[Invite] = {
    val query = s"""
      select $InviteSelectListItems
      from invites3
      where site_id = ? and secret_key = ?
      """
    val values = List(siteId.asAnyRef, secretKey)
    runQueryFindOneOrNone(query, values, rs => {
      getInvite(rs)
    }, "EdEB2KD0F")
  }


  def loadInvites(createdById: UserId): immutable.Seq[Invite] = {
    loadInvitesImpl(createdById = Some(createdById), limit = 999)
  }


  def loadAllInvites(limit: Int): immutable.Seq[Invite] = {
    loadInvitesImpl(createdById = None, limit = limit)
  }


  private def loadInvitesImpl(createdById: Option[UserId], limit: Int): immutable.Seq[Invite] = {
    val values = ArrayBuffer[AnyRef](siteId.asAnyRef)
    val andSentBy = createdById match {
      case Some(id) =>
        values.append(id.asAnyRef)
        "and created_by_id = ?"
      case None =>
        ""
    }
    val query = s"""
      select $InviteSelectListItems
      from invites3
      where site_id = ? $andSentBy
      order by created_at desc
      """
    runQueryFindMany(query, values.toList, rs => {
      getInvite(rs)
    })
  }


  def insertGroup(group: Group) {
    val sql = """
      insert into users3(site_id, user_id, username, full_name, created_at)
      values (?, ?, ?, ?, ?)
      """
    val values = List(siteId.asAnyRef, group.id.asAnyRef, group.theUsername, group.name,
      now.asTimestamp)
    runUpdateExactlyOneRow(sql, values)
  }


  def updateGroup(group: Group) {
    val statement = """
      update users3 set
        updated_at = now_utc(),
        full_name = ?,
        username = ?,
        summary_email_interval_mins = ?,
        summary_email_if_active = ?
      where site_id = ?
        and user_id = ?
      """

    val values = List(
      group.anyName.orNullVarchar,
      group.theUsername,
      // group.emailForEveryNewPost.asAnyRef,
      group.summaryEmailIntervalMins.orNullInt,
      group.summaryEmailIfActive.orNullBoolean,
      siteId.asAnyRef,
      group.id.asAnyRef)

    try runUpdateSingleRow(statement, values)
    catch {
      case ex: js.SQLException if isUniqueConstrViolation(ex) && uniqueConstrViolatedIs(
        "dw1_users_site_usernamelower__u", ex) =>
        throw DuplicateUsernameException(group.theUsername)
    }
  }


  def nextMemberId: UserId = {
    val query = s"""
      select max(user_id) max_id from users3
      where site_id = ? and user_id >= $LowestAuthenticatedUserId
      """
    runQuery(query, List(siteId.asAnyRef), rs => {
      rs.next()
      val maxId = rs.getInt("max_id")
      math.max(LowestAuthenticatedUserId, maxId + 1)
    })
  }


  def nextIdentityId: IdentityId = {
    val query = s"""
      select max(id) max_id from identities3
      where site_id = ?
      """
    runQuery(query, List(siteId.asAnyRef), rs => {
      rs.next()
      val maxId = rs.getInt("max_id")
      (maxId + 1).toString
    })
  }


  def insertMember(user: MemberInclDetails) {
    try {
      runUpdate("""
        insert into users3(
            SITE_ID, USER_ID, full_name, USERNAME, CREATED_AT,
            primary_email_addr, EMAIL_NOTFS, EMAIL_VERIFIED_AT, EMAIL_FOR_EVERY_NEW_POST, PASSWORD_HASH,
            IS_APPROVED, APPROVED_AT, APPROVED_BY_ID,
            COUNTRY, IS_OWNER, IS_ADMIN, IS_MODERATOR,
            about, see_activity_min_trust_level,
            trust_level, locked_trust_level, threat_level, locked_threat_level,
            deactivated_at, deleted_at)
        values (
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?, ?)
        """,
        List[AnyRef](siteId.asAnyRef, user.id.asAnyRef, user.fullName.orNullVarchar,
          user.username, user.createdAt, user.primaryEmailAddress.trimNullVarcharIfBlank,
          _toFlag(user.emailNotfPrefs), o2ts(user.emailVerifiedAt),
          user.emailForEveryNewPost.asTrueOrNull,
          user.passwordHash.orNullVarchar,
          user.isApproved.orNullBoolean, user.approvedAt.orNullTimestamp,
          user.approvedById.orNullInt,
          user.country.trimOrNullVarchar, user.isOwner.asTrueOrNull, user.isAdmin.asTrueOrNull,
          user.isModerator.asAnyRef,
          user.about.orNullVarchar, user.seeActivityMinTrustLevel.map(_.toInt).orNullInt,
          user.trustLevel.toInt.asAnyRef, user.lockedTrustLevel.map(_.toInt).orNullInt,
          user.threatLevel.toInt.asAnyRef, user.lockedThreatLevel.map(_.toInt).orNullInt,
          user.deactivatedAt.orNullTimestamp, user.deletedAt.orNullTimestamp))
    }
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex))
          throw ex

        if (uniqueConstrViolatedIs("users_site_primaryemail_u", ex))
          throw DbDao.DuplicateUserEmail(user.primaryEmailAddress)

        if (uniqueConstrViolatedIs("DW1_USERS_SITE_USERNAMELOWER__U", ex))
          throw DbDao.DuplicateUsername(user.username)

        throw ex
    }
  }


  def deleteAllUsersIdentities(userId: UserId) {
    TESTS_MISSING
    val statement =
      "delete from identities3 where user_id = ? and site_id = ?"
    val values = List[AnyRef](userId.asAnyRef, siteId.asAnyRef)
    runUpdate(statement, values)
  }


  def insertIdentity(identity: Identity) {
    identity match {
      case x: IdentityOpenId =>
        insertOpenIdIdentity(siteId, x)
      case x: OpenAuthIdentity =>
        insertOpenAuthIdentity(siteId, x)
      case x =>
        die("DwE8UYM0", s"Unknown identity type: ${classNameOf(x)}")
    }
  }


  private[rdb] def insertOpenIdIdentity(otherSiteId: SiteId, identity: IdentityOpenId) {
    val details = identity.openIdDetails
    runUpdate("""
            insert into identities3(
                ID, SITE_ID, USER_ID, USER_ID_ORIG, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                OID_REALM, OID_ENDPOINT, OID_VERSION,
                FIRST_NAME, EMAIL, COUNTRY)
            values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            List[AnyRef](identity.id.toInt.asAnyRef, otherSiteId.asAnyRef, identity.userId.asAnyRef,
              identity.userId.asAnyRef,
              details.oidClaimedId, e2d(details.oidOpLocalId), e2d(details.oidRealm),
              e2d(details.oidEndpoint), e2d(details.oidVersion),
              e2d(details.firstName), details.email.orNullVarchar, e2d(details.country.trim)))
  }


  private[rdb] def _updateIdentity(identity: IdentityOpenId)
        (implicit connection: js.Connection) {
    val details = identity.openIdDetails
    db.update("""
      update identities3 set
          USER_ID = ?, OID_CLAIMED_ID = ?,
          OID_OP_LOCAL_ID = ?, OID_REALM = ?,
          OID_ENDPOINT = ?, OID_VERSION = ?,
          FIRST_NAME = ?, EMAIL = ?, COUNTRY = ?
      where ID = ? and SITE_ID = ?
      """,
      List[AnyRef](identity.userId.asAnyRef, details.oidClaimedId,
        e2d(details.oidOpLocalId), e2d(details.oidRealm),
        e2d(details.oidEndpoint), e2d(details.oidVersion),
        e2d(details.firstName), details.email.orNullVarchar, e2d(details.country.trim),
        identity.id.toInt.asAnyRef, siteId.asAnyRef))
  }


  private def insertOpenAuthIdentity(
        otherSiteId: SiteId, identity: OpenAuthIdentity) {
    val sql = """
        insert into identities3(
            ID, SITE_ID, USER_ID, USER_ID_ORIG,
            FIRST_NAME, LAST_NAME, FULL_NAME, EMAIL, AVATAR_URL,
            AUTH_METHOD, SECURESOCIAL_PROVIDER_ID, SECURESOCIAL_USER_ID)
        values (
            ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?)"""
    val ds = identity.openAuthDetails
    val method = "OAuth" // should probably remove this column
    val values = List[AnyRef](
      identity.id.toInt.asAnyRef, otherSiteId.asAnyRef, identity.userId.asAnyRef,
      identity.userId.asAnyRef,
      ds.firstName.orNullVarchar, ds.lastName.orNullVarchar,
      ds.fullName.orNullVarchar, ds.email.orNullVarchar, ds.avatarUrl.orNullVarchar,
      method, ds.providerId, ds.providerKey)
    runUpdate(sql, values)
  }


  private[rdb] def updateOpenAuthIdentity(identity: OpenAuthIdentity)
        (implicit connection: js.Connection) {
    val sql = """
      update identities3 set
        USER_ID = ?, AUTH_METHOD = ?,
        FIRST_NAME = ?, LAST_NAME = ?, FULL_NAME = ?, EMAIL = ?, AVATAR_URL = ?
      where ID = ? and SITE_ID = ?"""
    val ds = identity.openAuthDetails
    val method = "OAuth" // should probably remove this column
    val values = List[AnyRef](
      identity.userId.asAnyRef, method, ds.firstName.orNullVarchar, ds.lastName.orNullVarchar,
      ds.fullName.orNullVarchar, ds.email.orNullVarchar, ds.avatarUrl.orNullVarchar,
      identity.id.toInt.asAnyRef, siteId.asAnyRef)
    db.update(sql, values)
  }


  def loadIdtyDetailsAndUser(userId: UserId): Option[(Identity, User)] = {
    db.withConnection(implicit connection => {
      _loadIdtyDetailsAndUser(forUserId = Some(userId)) match {
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
        forUserId: Option[UserId] = None,
        forOpenIdDetails: OpenIdDetails = null,
        forOpenAuthProfile: OpenAuthProviderIdKey = null)(implicit connection: js.Connection)
        : (Option[Identity], Option[Member]) = {
    val anyOpenIdDetails = Option(forOpenIdDetails)
    val anyOpenAuthKey = Option(forOpenAuthProfile)

    val anyUserId = forUserId

    var sqlSelectFrom = """
        select
            """+ UserSelectListItemsNoGuests +""",
            i.ID i_id,
            i.OID_CLAIMED_ID,
            i.OID_OP_LOCAL_ID,
            i.OID_REALM,
            i.OID_ENDPOINT,
            i.OID_VERSION,
            i.SECURESOCIAL_USER_ID,
            i.SECURESOCIAL_PROVIDER_ID,
            i.AUTH_METHOD,
            i.FIRST_NAME i_first_name,
            i.LAST_NAME i_last_name,
            i.FULL_NAME i_full_name,
            i.EMAIL i_email,
            i.COUNTRY i_country,
            i.AVATAR_URL i_avatar_url
          from identities3 i inner join users3 u
            on i.SITE_ID = u.SITE_ID
            and i.USER_ID = u.USER_ID
        """

    val (whereClause, bindVals) = (anyUserId, anyOpenIdDetails, anyOpenAuthKey) match {

      case (Some(userId: UserId), None, None) =>
        ("""where i.SITE_ID = ? and i.USER_ID = ?""",
           List(siteId.asAnyRef, userId.asAnyRef))

      case (None, Some(openIdDetails: OpenIdDetails), None) =>
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
          ("i.OID_CLAIMED_ID = ?", openIdDetails.oidClaimedId)
        }
        ("""where i.SITE_ID = ?
            and """+ claimedIdOrEmailCheck +"""
          """, List(siteId.asAnyRef, idOrEmail))

      case (None, None, Some(openAuthKey: OpenAuthProviderIdKey)) =>
        val whereClause =
          """where i.SITE_ID = ?
               and i.SECURESOCIAL_PROVIDER_ID = ?
               and i.SECURESOCIAL_USER_ID = ?"""
        val values = List(siteId.asAnyRef, openAuthKey.providerId, openAuthKey.providerKey)
        (whereClause, values)

      case _ => assErr("DwE98239k2a2", "None, or more than one, lookup method specified")
    }

    db.query(sqlSelectFrom + whereClause, bindVals, rs => {
      if (!rs.next)
        return None -> None

      // Warning: Some dupl code in _loadIdtysAndUsers:
      // COULD break out construction of Identity to reusable
      // functions.

      val userInDb: Member = _User(rs).toMemberOrThrow
      val id = rs.getInt("i_id").toString
      val email = Option(rs.getString("i_email"))
      val anyClaimedOpenId = Option(rs.getString("OID_CLAIMED_ID"))
      val anyOpenAuthProviderId = Option(rs.getString("SECURESOCIAL_PROVIDER_ID"))

      val identityInDb = {
        if (anyClaimedOpenId.nonEmpty) {
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
          assErr("DwE77GJ2", s"Unknown identity in identities3, site: $siteId, id: $id")
        }
      }

      assErrIf(rs.next, "DwE53IK24", "More that one matching identity, when"+
         " looking up: "+ (anyUserId, anyOpenIdDetails, anyOpenAuthKey))

      Some(identityInDb) -> Some(userInDb)
    })
  }


  def loadMemberByPrimaryEmailOrUsername(emailOrUsername: String): Option[Member] = {
    loadMemberByPrimaryEmailOrUsernameImpl(emailOrUsername)(theOneAndOnlyConnection)
  }


  private def loadMemberByPrimaryEmailOrUsernameImpl(emailOrUsername: String)
        (implicit connection: js.Connection): Option[Member] = {
    val sql = s"""
      select $UserSelectListItemsNoGuests
      from users3 u
      where u.SITE_ID = ?
        and u.USER_ID >= $LowestNonGuestId
        and (u.primary_email_addr = ? or lower(u.USERNAME) = lower(?))
      """
    val values = List(siteId.asAnyRef, emailOrUsername, emailOrUsername)
    runQueryFindOneOrNone(sql, values, rs => {
      _User(rs).toMemberOrThrow
    })
  }


  def loadUser(userId: UserId): Option[User] =
    loadUsersAsSeq(userId::Nil).headOption


  def loadUsers(userIds: Iterable[UserId]): immutable.Seq[User] =
    loadUsersAsSeq(userIds.toList)


  def loadUsersAsSeq(userIds: immutable.Seq[UserId]): List[User] = {
    val usersBySiteAndId = asSystem.loadUsers(Map(siteId -> userIds))
    usersBySiteAndId.values.toList
  }


  def loadUsersAsMap(userIds: Iterable[UserId]): Map[UserId, User] = {
    val usersBySiteAndId = asSystem.loadUsers(Map(siteId -> userIds.toList))
    usersBySiteAndId map { case (siteAndUserId, user) =>
      siteAndUserId._2 -> user
    }
  }


  def loadMembersWithPrefix(usernamePrefix: String): immutable.Seq[Member] = {
    // Would it be better UX to do lowercase match?
    val withPrefixAnd = usernamePrefix.isEmpty ? "" | "username like ? and"
    val query = i"""
      select $UserSelectListItemsNoGuests
      from users3 u
      where $withPrefixAnd u.site_id = ?
        and u.user_id >= $LowestTalkToMemberId
        and u.trust_level is not null
      """
    var values = List(siteId.asAnyRef)
    if (withPrefixAnd.nonEmpty) {
      values ::= usernamePrefix + '%'
    }
    runQueryFindMany(query, values, readMember)
  }


  def loadUsers(): immutable.Seq[User] = {
    val query = i"""
      select $UserSelectListItemsWithGuests
      from users3 u
      left join guest_prefs3 e
        on u.guest_email_addr = e.EMAIL and u.SITE_ID = e.SITE_ID
      where
        u.SITE_ID = ?
      """
    runQueryFindMany(query, List(siteId.asAnyRef), _User)
  }


  def loadGroupsAsSeq(): immutable.Seq[Group] = {
    // The null checks below: groups have no trust level, but all members do. [8KPG2W5]
    val query = i"""
      select $GroupSelectListItems
      from users3
      where site_id = ?
        and username is not null
        and trust_level is null
      """
    runQueryFindMany(query, List(siteId.asAnyRef), getGroup)
  }


  def loadMemberOrGroupInclDetailsByUsername(username: String): Option[MemberOrGroupInclDetails] = {
    loadMemberOrGroupInclDetailsImpl("lower(username)", username.toLowerCase)
  }


  def loadMemberOrGroupInclDetails(userId: UserId): Option[MemberOrGroupInclDetails] = {
    require(User.isRoleId(userId), "DwE5FKE2")
    loadMemberOrGroupInclDetailsImpl("user_id", userId.asAnyRef)
  }


  def loadOwner(): Option[MemberInclDetails] = {
    loadMemberOrGroupInclDetailsImpl("is_owner", true.asAnyRef) map {
      case member: MemberInclDetails => member
      case group: Group => die("EdE2QYTK05", s"Owner ${group.id}@$siteId is a group")
    }
  }


  private def loadMemberOrGroupInclDetailsImpl(field: String, value: AnyRef)
        : Option[MemberOrGroupInclDetails] = {
    val sql = s"""
      select $CompleteUserSelectListItemsWithUserId
      from users3
      where site_id = ? and $field = ?
      """
    runQueryFindOneOrNone(sql, List(siteId.asAnyRef, value), rs => {
      getCompleteUser(rs)
    })
  }


  def loadMembersAndGroupsInclDetailsById(userIds: Iterable[UserId])
        : immutable.Seq[MemberOrGroupInclDetails] = {
    if (userIds.isEmpty) return Nil
    val query = s"""
      select $CompleteUserSelectListItemsWithUserId
      from users3
      where site_id = ? and user_id in (${makeInListFor(userIds)})
      """
    val values = siteId.asAnyRef :: userIds.toList.map(_.asAnyRef)
    runQueryFindMany(query, values, rs => {
      getCompleteUser(rs)
    })
  }


  def loadMembersInclDetails(
        onlyApproved: Boolean = false,
        onlyPendingApproval: Boolean = false): immutable.Seq[MemberInclDetails] = {
    require(!onlyApproved || !onlyPendingApproval)

    val andIsApprovedEqWhatever =
      if (onlyPendingApproval) s"and (is_approved is null and not $IsOwnerOrStaff)"
      else if (onlyApproved) s"and (is_approved = true or $IsOwnerOrStaff)"
      else ""

    val anyOrderBy =
      if (onlyPendingApproval) "order by created_at desc, user_id desc"
      else ""

    val query = s"""
      select $CompleteUserSelectListItemsWithUserId
      from users3 u
      where
        u.site_id = ? and
        u.user_id >= ${User.LowestAuthenticatedUserId} and
        u.trust_level is not null   -- currently always null for groups [1WBK5JZ0]
        $andIsApprovedEqWhatever
        $anyOrderBy
      """

    runQueryFindMany(query, List(siteId.asAnyRef), rs => {
      getCompleteUser(rs) match {
        case m: MemberInclDetails => m
        case g: Group => throw GotAGroupException(g.id)
      }
    })
  }


  def updateMemberInclDetails(user: MemberInclDetails): Boolean = {
    val statement = """
      update users3 set
        updated_at = now_utc(),
        full_name = ?,
        username = ?,
        primary_email_addr = ?,
        email_verified_at = ?,
        email_for_every_new_post = ?,
        email_notfs = ?,
        summary_email_interval_mins = ?,
        summary_email_if_active = ?,
        password_hash = ?,
        country = ?,
        website = ?,
        about = ?,
        see_activity_min_trust_level = ?,
        avatar_tiny_base_url = ?,
        avatar_tiny_hash_path = ?,
        avatar_small_base_url = ?,
        avatar_small_hash_path = ?,
        avatar_medium_base_url = ?,
        avatar_medium_hash_path = ?,
        is_approved = ?,
        approved_at = ?,
        approved_by_id = ?,
        suspended_at = ?,
        suspended_till = ?,
        suspended_by_id = ?,
        suspended_reason = ?,
        trust_level = ?,
        locked_trust_level = ?,
        threat_level = ?,
        locked_threat_level = ?,
        is_owner = ?,
        is_admin = ?,
        is_moderator = ?,
        deactivated_at = ?,
        deleted_at = ?
      where site_id = ? and user_id = ?
      """

    val values = List(
      user.fullName.orNullVarchar,
      user.username,
      user.primaryEmailAddress.trimNullVarcharIfBlank,
      user.emailVerifiedAt.orNullTimestamp,
      user.emailForEveryNewPost.asAnyRef,
      _toFlag(user.emailNotfPrefs),
      user.summaryEmailIntervalMins.orNullInt,
      user.summaryEmailIfActive.orNullBoolean,
      user.passwordHash.orNullVarchar,
      user.country.trimOrNullVarchar,
      user.website.trimOrNullVarchar,
      user.about.trimOrNullVarchar,
      user.seeActivityMinTrustLevel.map(_.toInt).orNullInt,
      user.tinyAvatar.map(_.baseUrl).orNullVarchar,
      user.tinyAvatar.map(_.hashPath).orNullVarchar,
      user.smallAvatar.map(_.baseUrl).orNullVarchar,
      user.smallAvatar.map(_.hashPath).orNullVarchar,
      user.mediumAvatar.map(_.baseUrl).orNullVarchar,
      user.mediumAvatar.map(_.hashPath).orNullVarchar,
      user.isApproved.orNullBoolean,
      user.approvedAt.orNullTimestamp,
      user.approvedById.orNullInt,
      user.suspendedAt.orNullTimestamp,
      user.suspendedTill.orNullTimestamp,
      user.suspendedById.orNullInt,
      user.suspendedReason.orNullVarchar,
      user.trustLevel.toInt.asAnyRef,
      user.lockedTrustLevel.map(_.toInt).orNullInt,
      user.threatLevel.toInt.asAnyRef,
      user.lockedThreatLevel.map(_.toInt).orNullInt,
      user.isOwner.asTrueOrNull,
      user.isAdmin.asTrueOrNull,
      user.isModerator.asTrueOrNull,
      user.deactivatedAt.orNullTimestamp,
      user.deletedAt.orNullTimestamp,
      siteId.asAnyRef,
      user.id.asAnyRef)

    try runUpdateSingleRow(statement, values)
    catch {
      case ex: js.SQLException if isUniqueConstrViolation(ex) && uniqueConstrViolatedIs(
          "dw1_users_site_usernamelower__u", ex) =>
        throw DuplicateUsernameException(user.username)
    }
  }


  def updateGuest(user: Guest): Boolean = {
    val statement = """
      update users3 set
        updated_at = now_utc(),
        full_name = ?,
        locked_threat_level = ?
      where site_id = ? and user_id = ?
      """
    val values = List(user.guestName, user.lockedThreatLevel.map(_.toInt).orNullInt,
      siteId.asAnyRef, user.id.asAnyRef)

    try runUpdateSingleRow(statement, values)
    catch {
      case ex: js.SQLException =>
        if (isUniqueConstrViolation(ex) && uniqueConstrViolatedIs("users_site_guest_u", ex))
          throw DbDao.DuplicateGuest
        else
          throw ex
    }
  }


  def listUsernames(pageId: PageId, prefix: String): Seq[NameAndUsername] = {
    if (prefix.isEmpty) {
      listUsernamesOnPage(pageId)
    }
    else {
      listUsernamesWithPrefix(prefix)
    }
  }


  private def listUsernamesOnPage(pageId: PageId): Seq[NameAndUsername] = {
    val sql = """
      select distinct u.user_id, u.full_name, u.USERNAME
      from posts3 p inner join users3 u
         on p.SITE_ID = u.SITE_ID
        and p.CREATED_BY_ID = u.USER_ID
        and u.USERNAME is not null
      where p.SITE_ID = ? and p.PAGE_ID = ?"""
    val values = List(siteId.asAnyRef, pageId)
    val result = ArrayBuffer[NameAndUsername]()
    db.queryAtnms(sql, values, rs => {
      while (rs.next()) {
        val userId = rs.getInt("user_id")
        val fullName = Option(rs.getString("full_name")) getOrElse ""
        val username = rs.getString("USERNAME")
        dieIf(username eq null, "DwE5BKG1")
        result += NameAndUsername(userId, fullName = fullName, username = username)
      }
    })
    result.to[immutable.Seq]
  }


  private def listUsernamesWithPrefix(prefix: String): Seq[NameAndUsername] = {
    val sql = s"""
      select distinct user_id, full_name, USERNAME
      from users3
      where SITE_ID = ? and lower(USERNAME) like lower(?) and USER_ID >= $LowestNonGuestId
      """
    val values = List(siteId.asAnyRef, prefix + "%")
    val result = ArrayBuffer[NameAndUsername]()
    db.queryAtnms(sql, values, rs => {
      while (rs.next()) {
        result += NameAndUsername(
          id = rs.getInt("user_id"),
          fullName = Option(rs.getString("full_name")) getOrElse "",
          username = rs.getString("USERNAME"))
      }
    })
    result.to[immutable.Seq]
  }


  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs) {
    transactionCheckQuota { implicit connection =>
      // SECURITY should stop remembering old rows, or prune table if too many old rows. And after that,
      // start allowing over quota here (since will be an update only).

      // Mark the current row as 'O' (old) -- unless EMAIL_NOTFS is 'F'
      // (Forbidden Forever). Then leave it as is, and let the insert
      // below fail.
      // COULD check # rows updated? No, there might be no rows to update.
      db.update("""
          update guest_prefs3
          set VERSION = 'O' -- old
          where SITE_ID = ? and EMAIL = ? and VERSION = 'C'
            and EMAIL_NOTFS != 'F'
          """,
          List(siteId.asAnyRef, emailAddr))

      // Create a new row with the desired email notification setting.
      // Or, for now, fail and throw some SQLException if EMAIL_NOTFS is 'F'
      // for this `emailAddr' -- since there'll be a primary key violation,
      // see the update statement above.
      db.update("""
          insert into guest_prefs3 (
              SITE_ID, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, 'C', ?, ?)
          """,
          List(siteId.asAnyRef, d2ts(ctime), emailAddr, _toFlag(emailNotfPrefs)))
    }
  }


  // COULD move to PageUsersSiteDaoMixin.
  def loadUserPageSettings(userId: UserId, pageId: PageId): Option[UserPageSettings] = {
    val query = i"""
      select notf_level
      from page_users3
      where site_id = ? and user_id = ? and page_id = ?
      """
    val values = List(siteId.asAnyRef, userId.asAnyRef, pageId)
    runQueryFindOneOrNone(query, values, rs => {
      val notfLevel = NotfLevel.fromInt(rs.getInt("notf_level")).getOrElse(NotfLevel.Normal)
      UserPageSettings(notfLevel)
    })
  }


  // COULD move to PageUsersSiteDaoMixin.
  def saveUserPageSettings(userId: UserId, pageId: PageId, settings: UserPageSettings)  {
    val sql = """
      insert into page_users3(site_id, user_id, page_id, notf_level)
      values (?, ?, ?, ?)
      on conflict (site_id, user_id, page_id) do update set
        notf_level = excluded.notf_level
      """
    val values = List(siteId.asAnyRef, userId.asAnyRef, pageId, settings.notfLevel.toInt.asAnyRef)
    runUpdate(sql, values)
  }


  def loadUserIdsWatchingPage(pageId: PageId): Seq[UserId] = {
    TESTS_MISSING // tested via e2e tests but not via app server tests
    val result = ArrayBuffer[UserId]()

    // Load people watching pageId only.
    // For now, ignore users watching any parent categories, consider `pageId` itself only.
    // COULD move this query only to PageUsersSiteDaoMixin.
    val sql = s"""
      select user_id from page_users3
      where site_id = ?
        and page_id = ?
        and notf_level = ${NotfLevel.WatchingAll.toInt}
      """
    runQuery(sql, List(siteId.asAnyRef, pageId), rs => {
      while (rs.next()) {
        result += rs.getInt("user_id")
      }
    })

    // Load people watching the whole site.
    val sqlWholeSite = """
      select USER_ID from users3 where SITE_ID = ? and EMAIL_FOR_EVERY_NEW_POST = true"""
    runQuery(sqlWholeSite, List(siteId.asAnyRef), rs => {
      while (rs.next()) {
        result += rs.getInt("USER_ID")
      }
    })
    result.distinct.to[immutable.Seq]
  }

}



