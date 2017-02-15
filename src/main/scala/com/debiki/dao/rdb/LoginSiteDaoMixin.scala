/**
 * Copyright (C) 2013 Kaj Magnus Lindberg (born 1979)
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
import com.debiki.core.Prelude._
import com.debiki.core.User.MaxCustomGuestId
import java.{sql => js, util => ju}
import Rdb._
import RdbUtil._



object LoginSiteDaoMixin {

}



trait LoginSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>


  override def tryLoginAsMember(loginAttempt: MemberLoginAttempt): MemberLoginGrant = {
    val loginGrant = loginAttempt match {
      case x: PasswordLoginAttempt => loginWithPassword(x)
      case x: EmailLoginAttempt => loginWithEmailId(x)
      case x: OpenIdLoginAttempt => loginOpenId(x)
      case x: OpenAuthLoginAttempt => loginOpenAuth(x)
    }
    loginGrant
  }


  override def loginAsGuest(loginAttempt: GuestLoginAttempt): GuestLoginResult = {
      var userId = 0
      var emailNotfsStr = ""
      var isNewGuest = false
      for (i <- 1 to 2 if userId == 0) {
        runQuery("""
          select u.USER_ID, g.EMAIL_NOTFS from users3 u
            left join guest_prefs3 g
                   on u.site_id = g.site_id
                  and u.EMAIL = g.EMAIL
                  and g.VERSION = 'C'
          where u.SITE_ID = ?
            and u.full_name = ?
            and u.EMAIL = ?
            and u.GUEST_COOKIE = ?
          """,
          List(siteId, e2d(loginAttempt.name), e2d(loginAttempt.email), loginAttempt.guestCookie),
          rs => {
            if (rs.next) {
              userId = rs.getInt("USER_ID")
              emailNotfsStr = rs.getString("EMAIL_NOTFS")
            }
          })

        if (userId == 0) {
          // We need to create a new guest user.
          // There is a unique constraint on SITE_ID, NAME, EMAIL, LOCATION, URL,
          // so this insert might fail (if another thread does
          // the insert, just before). Should it fail, the above `select'
          // is run again and finds the row inserted by the other thread.
          // Could avoid logging any error though!
          isNewGuest = true
          runUpdate(i"""
            insert into users3(
              site_id, user_id, created_at, full_name, email, guest_cookie)
            select
              ?, least(min(user_id) - 1, $MaxCustomGuestId), now_utc(), ?, ?, ?
            from
              users3 where site_id = ?
            """,
            List(siteId, loginAttempt.name.trim, e2d(loginAttempt.email),
              loginAttempt.guestCookie, siteId))
          // (Could fix: `returning ID into ?`, saves 1 roundtrip.)
          // Loop one more lap to read ID.
        }
      }
      dieIf(userId == 0, "DwE3kRhk20")

      val user = Guest(
        id = userId,
        guestName = loginAttempt.name,
        guestCookie = Some(loginAttempt.guestCookie),
        email = loginAttempt.email,
        emailNotfPrefs = _toEmailNotfs(emailNotfsStr),
        country = None)

      GuestLoginResult(user, isNewGuest)
  }


  private def loginWithPassword(loginAttempt: PasswordLoginAttempt): MemberLoginGrant = {
    val anyUser = loadMemberByEmailOrUsername(loginAttempt.email)
    val user = anyUser getOrElse {
      throw NoMemberWithThatEmailException
    }
    if (user.emailVerifiedAt.isEmpty) {
      throw DbDao.EmailNotVerifiedException
    }
    val correctHash = user.passwordHash getOrElse {
      throw MemberHasNoPasswordException
    }
    val okPassword = checkPassword(loginAttempt.password, hash = correctHash)
    if (!okPassword)
      throw BadPasswordException

    MemberLoginGrant(identity = None, user, isNewIdentity = false, isNewMember = false)
  }


  private def loginWithEmailId(loginAttempt: EmailLoginAttempt): MemberLoginGrant = {
    val emailId = loginAttempt.emailId
    val email: Email = loadEmailById(emailId = emailId) match {
      case Some(email) if email.toUserId.isDefined => email
      case Some(email) => throw BadEmailTypeException(emailId)
      case None => throw EmailNotFoundException(emailId)
    }
    val user = loadMember(email.toUserId.get) match {
      case Some(user) => user
      case None =>
        runErr("DwE2XKw5", o"""User `${email.toUserId}"' not found
           when logging in with email id `$emailId'.""")
    }
    if (user.email != email.sentTo)
      throw new EmailAddressChangedException(email, user)

    val idtyWithId = IdentityEmailId(id = emailId, userId = user.id, emailSent = Some(email))
    MemberLoginGrant(Some(idtyWithId), user, isNewIdentity = false, isNewMember = false)
  }


  private def loginOpenId(loginAttempt: OpenIdLoginAttempt): MemberLoginGrant = {
    die("EsE6UYKJ2", "Unimpl") /*
    transactionCheckQuota { implicit connection =>

    // Load any matching Identity and the related User.
      val (identityInDb: Option[Identity], userInDb: Option[User]) =
        _loadIdtyDetailsAndUser(forOpenIdDetails = loginAttempt.openIdDetails)

      // Create user if absent.
      val user = userInDb match {
        case Some(u) => u
        case None =>
          ??? /* Don't create a new user from here
          val details = loginAttempt.openIdDetails
          val userNoId =  User(
            id = "?",
            displayName = details.firstName,
            email = details.email getOrElse "",
            emailNotfPrefs = EmailNotfPrefs.Unspecified,
            country = details.country,
            website = "",
            isAdmin = false,
            isOwner = false)

          val userWithId = _insertUser(siteId, userNoId)
          userWithId
          */
      }

      // Create or update the OpenID identity.
      //
      // (It's absent, if this is the first time the user logs in.
      // It needs to be updated, if the user has changed e.g. her
      // OpenID name or email.)
      //
      // (Concerning simultaneous inserts/updates by different threads or
      // server nodes: This insert might result in a unique key violation
      // error. Simply let the error propagate and the login fail.
      // This login was supposedly initiated by a human, and there is
      // no point in allowing exactly simultaneous logins by one
      // single human.)

      val identity = identityInDb match {
        case None =>
          ??? /* Don't create a new identity from here
          val identityNoId = IdentityOpenId(id = "?", userId = user.id, loginAttempt.openIdDetails)
          insertOpenIdIdentity(siteId, identityNoId)(connection)
          */
        case Some(old: IdentityOpenId) =>
          val nev = IdentityOpenId(id = old.id, userId = user.id, loginAttempt.openIdDetails)
          if (nev != old) {
            assErrIf(nev.openIdDetails.oidClaimedId != old.openIdDetails.oidClaimedId, "DwE73YQ2")
            _updateIdentity(nev)
          }
          nev
        case x => throwBadDatabaseData("DwE26DFW0", s"A non-OpenID identity found in database: $x")
      }

      LoginGrant(Some(identity), user, isNewIdentity = identityInDb.isEmpty,
        isNewRole = userInDb.isEmpty)
    }
    */
  }


  private def loginOpenAuth(loginAttempt: OpenAuthLoginAttempt): MemberLoginGrant = {
    transactionCheckQuota { connection =>
      loginOpenAuthImpl(loginAttempt)(connection)
    }
  }


  private def loginOpenAuthImpl(loginAttempt: OpenAuthLoginAttempt)
        (connection: js.Connection): MemberLoginGrant = {
    val (identityInDb: Option[Identity], userInDb: Option[Member]) =
      _loadIdtyDetailsAndUser(
        forOpenAuthProfile = loginAttempt.profileProviderAndKey)(connection)

    val user = userInDb match {
      case Some(u) => u
      case None => throw IdentityNotFoundException
    }

    // (For some unimportant comments, see the corresponding comment in loginOpenId() above.)

    val identity: OpenAuthIdentity = identityInDb match {
      case None => throw IdentityNotFoundException
      case Some(old: OpenAuthIdentity) =>
        val nev = OpenAuthIdentity(id = old.id, userId = user.id,
          loginAttempt.openAuthDetails)
        if (nev != old) {
          updateOpenAuthIdentity(nev)(connection)
        }
        nev
      case x =>
        throwBadDatabaseData("DwE21GSh0", s"A non-SecureSocial identity found in database: $x")
    }

    MemberLoginGrant(Some(identity), user, isNewIdentity = identityInDb.isEmpty,
      isNewMember = userInDb.isEmpty)
  }

}
