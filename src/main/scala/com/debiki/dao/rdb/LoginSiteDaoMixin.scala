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
import com.debiki.core.EmailNotfPrefs.EmailNotfPrefs
import com.debiki.core.Prelude._
//import com.debiki.core.SecureSocialIdentity.displayNameFor
import java.{sql => js, util => ju}
import Rdb._
import RdbUtil._



object LoginSiteDaoMixin {

}



trait LoginSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  override def tryLogin(loginAttempt: LoginAttempt): LoginGrant = {
    val loginGrant = loginAttempt match {
      case x: PasswordLoginAttempt => loginWithPassword(x)
      case x: EmailLoginAttempt => loginWithEmailId(x)
      case x: OpenIdLoginAttempt => loginOpenId(x)
      case x: OpenAuthLoginAttempt => loginOpenAuth(x)
    }
    loginGrant
  }


  override def loginAsGuest(loginAttempt: GuestLoginAttempt): GuestLoginResult = {
    db.transaction { implicit connection =>
      var idtyId = ""
      var emailNotfsStr = ""
      var isNewGuest = false
      for (i <- 1 to 2 if idtyId.isEmpty) {
        db.query("""
            select g.ID, e.EMAIL_NOTFS from DW1_GUESTS g
              left join DW1_IDS_SIMPLE_EMAIL e
              on g.EMAIL_ADDR = e.EMAIL and e.VERSION = 'C'
            where g.SITE_ID = ?
              and g.NAME = ?
              and g.EMAIL_ADDR = ?
              and g.LOCATION = ?
              and g.URL = ?
                 """,
          List(siteId, e2d(loginAttempt.name), e2d(loginAttempt.email),
            e2d(loginAttempt.location), e2d(loginAttempt.website)),
          rs => {
            if (rs.next) {
              idtyId = rs.getString("ID")
              emailNotfsStr = rs.getString("EMAIL_NOTFS")
            }
          })
        if (idtyId isEmpty) {
          // Create simple user info.
          // There is a unique constraint on SITE_ID, NAME, EMAIL, LOCATION, URL,
          // so this insert might fail (if another thread does
          // the insert, just before). Should it fail, the above `select'
          // is run again and finds the row inserted by the other thread.
          // Could avoid logging any error though!
          isNewGuest = true
          db.update("""
              insert into DW1_GUESTS(
                  SITE_ID, ID, NAME, EMAIL_ADDR, LOCATION, URL)
              values (?, nextval('DW1_IDS_SNO'), ?, ?, ?, ?)""",
            List(siteId, loginAttempt.name, e2d(loginAttempt.email),
              e2d(loginAttempt.location), e2d(loginAttempt.website)))
          // (Could fix: `returning ID into ?`, saves 1 roundtrip.)
          // Loop one more lap to read ID.
        }
      }
      assErrIf3(idtyId.isEmpty, "DwE3kRhk20")
      val notfPrefs: EmailNotfPrefs = _toEmailNotfs(emailNotfsStr)

      // Derive a temporary user from the identity, see
      // Debiki for Developers #9xdF21.
      val user = User(id = _dummyUserIdFor(idtyId),
        displayName = loginAttempt.name,
        email = loginAttempt.email,
        emailNotfPrefs = notfPrefs,
        country = "",
        website = loginAttempt.website,
        isAdmin = false,
        isOwner = false)

      // Quota already consumed (in the `for` loop above).
      GuestLoginResult(user, isNewGuest)
    }
  }


  private def loginWithPassword(loginAttempt: PasswordLoginAttempt): LoginGrant = {
    val anyIdentityAndUser = loadIdtyDetailsAndUser(forEmailAddr = loginAttempt.email)

    val (identity: PasswordIdentity, user) = anyIdentityAndUser match {
      case Some((x: PasswordIdentity, user)) => (x, user)
      case _ => throw IdentityNotFoundException(
        s"No identity found with email: ${loginAttempt.email}")
    }

    val okPassword = checkPassword(loginAttempt.password, hash = identity.passwordSaltHash)
    if (!okPassword)
      throw BadPasswordException

    LoginGrant(identity, user, isNewIdentity = false, isNewRole = false)
  }


  private def loginWithEmailId(loginAttempt: EmailLoginAttempt): LoginGrant = {
    val emailId = loginAttempt.emailId
    val email: Email = loadEmailById(emailId = emailId) match {
      case Some(email) if email.toUserId.isDefined => email
      case Some(email) => throw BadEmailTypeException(emailId)
      case None => throw EmailNotFoundException(emailId)
    }
    val user = loadUser(email.toUserId.get) match {
      case Some(user) => user
      case None =>
        runErr("DwE2XKw5", o"""User `${email.toUserId}"' not found
           when logging in with email id `$emailId'.""")
    }
    val idtyWithId = IdentityEmailId(id = emailId, userId = user.id, emailSent = Some(email))
    LoginGrant(idtyWithId, user, isNewIdentity = false, isNewRole = false)
  }


  private def loginOpenId(loginAttempt: OpenIdLoginAttempt): LoginGrant = {
    db.transaction { implicit connection =>

    // Load any matching Identity and the related User.
      val (identityInDb: Option[Identity], userInDb: Option[User]) =
        _loadIdtyDetailsAndUser(forOpenIdDetails = loginAttempt.openIdDetails)

      // Create user if absent.
      val user = userInDb match {
        case Some(u) => u
        case None =>
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
          val identityNoId = IdentityOpenId(id = "?", userId = user.id, loginAttempt.openIdDetails)
          insertOpenIdIdentity(siteId, identityNoId)(connection)
        case Some(old: IdentityOpenId) =>
          val nev = IdentityOpenId(id = old.id, userId = user.id, loginAttempt.openIdDetails)
          if (nev != old) {
            if (nev.openIdDetails.isGoogleLogin)
              assErrIf(nev.openIdDetails.email != old.openIdDetails.email ||
                !old.openIdDetails.isGoogleLogin, "DwE3Bz6")
            else
              assErrIf(nev.openIdDetails.oidClaimedId != old.openIdDetails.oidClaimedId, "DwE73YQ2")
            _updateIdentity(nev)
          }
          nev
        case x => throwBadDatabaseData("DwE26DFW0", s"A non-OpenID identity found in database: $x")
      }

      LoginGrant(identity, user, isNewIdentity = identityInDb.isEmpty,
        isNewRole = userInDb.isEmpty)
    }
  }


  private def loginOpenAuth(loginAttempt: OpenAuthLoginAttempt): LoginGrant = {
    db.transaction { connection =>
      loginOpenAuthImpl(loginAttempt)(connection)
    }
  }


  private def loginOpenAuthImpl(loginAttempt: OpenAuthLoginAttempt)
        (connection: js.Connection): LoginGrant = {
    val (identityInDb: Option[Identity], userInDb: Option[User]) =
      _loadIdtyDetailsAndUser(
        forOpenAuthProfile = loginAttempt.profileProviderAndKey)(connection)

    // Create user if absent.

    val user = userInDb match {
      case Some(u) => u
      case None =>
        val userNoId =  User(
          id = "?",
          displayName = loginAttempt.openAuthDetails.displayName,
          email = loginAttempt.openAuthDetails.email getOrElse "",
          emailNotfPrefs = EmailNotfPrefs.Unspecified,
          country = "",
          website = "",
          isAdmin = false,
          isOwner = false)
        val userWithId = _insertUser(siteId, userNoId)(connection)
        userWithId
    }

    // Create or update the SecureSocial identity.
    // (For some unimportant comments, see the corresponding comment in loginOpenId() above.)

    val identity: OpenAuthIdentity = identityInDb match {
      case None =>
        val identityNoId = OpenAuthIdentity(id = "?", userId = user.id,
          loginAttempt.openAuthDetails)
        insertOpenAuthIdentity(siteId, identityNoId)(connection)
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

    LoginGrant(identity, user, isNewIdentity = identityInDb.isEmpty,
      isNewRole = userInDb.isEmpty)
  }

}
