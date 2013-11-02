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
import java.{sql => js, util => ju}
import Rdb._
import RdbUtil._



object LoginSiteDaoMixin {

}



trait LoginSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  override def saveLogin(loginReq: LoginRequest): LoginGrant = {

    // Assigns an id to `loginNoId', saves it and returns it (with id).
    def _saveLogin(loginNoId: Login, identityWithId: Identity)
                  (implicit connection: js.Connection): Login = {
      // Create a new _LOGINS row, pointing to identityWithId.
      val loginSno = db.nextSeqNo("DW1_LOGINS_SNO")
      val login = loginNoId.copy(id = loginSno.toString,
        identityId = identityWithId.id)
      val identityType = identityWithId match {
        case _: IdentitySimple => "Simple"
        case _: IdentityOpenId => "OpenID"
        case _: IdentityEmailId => "EmailID"
        case _ => assErr("DwE3k2r21K5")
      }
      db.update("""
          insert into DW1_LOGINS(
              SNO, TENANT, PREV_LOGIN, ID_TYPE, ID_SNO,
              LOGIN_IP, LOGIN_TIME)
          values (?, ?, ?,
              '"""+
        // Don't bind identityType, that'd only make it harder for
        // the optimizer.
        identityType +"', ?, ?, ?)",
        List(loginSno.asInstanceOf[AnyRef], siteId,
          e2n(login.prevLoginId),  // UNTESTED unless empty
          login.identityId, login.ip, login.date))
      login
    }

    def _loginWithEmailId(emailId: String): LoginGrant = {
      val (email: Email, notf: NotfOfPageAction) = (
        loadEmailById(emailId = emailId),
        loadNotfByEmailId(emailId = emailId)
        ) match {
        case (Some(email), Some(notf)) => (email, notf)
        case (None, _) =>
          throw EmailNotFoundException(emailId)
        case (_, None) =>
          runErr("DwE87XIE3", "Notification missing for email id: "+ emailId)
      }
      val user = _loadUser(notf.recipientUserId) match {
        case Some(user) => user
        case None =>
          runErr("DwE2XKw5", "User `"+ notf.recipientUserId +"' not found"+
            " when logging in with email id `"+ emailId +"'.")
      }
      val idtyWithId = IdentityEmailId(id = emailId, userId = user.id,
        emailSent = Some(email), notf = Some(notf))
      val loginWithId = db.transaction { implicit connection =>
        _saveLogin(loginReq.login, idtyWithId)
      }
      LoginGrant(loginWithId, idtyWithId, user, isNewIdentity = false,
        isNewRole = false)
    }

    def _loginUnauIdty(idtySmpl: IdentitySimple): LoginGrant = {
      db.transaction { implicit connection =>
        var idtyId = ""
        var emailNotfsStr = ""
        var createdNewIdty = false
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
            List(siteId, e2d(idtySmpl.name), e2d(idtySmpl.email),
              e2d(idtySmpl.location), e2d(idtySmpl.website)),
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
            createdNewIdty = true
            db.update("""
              insert into DW1_GUESTS(
                  SITE_ID, ID, NAME, EMAIL_ADDR, LOCATION, URL)
              values (?, nextval('DW1_IDS_SNO'), ?, ?, ?, ?)""",
              List(siteId, idtySmpl.name, e2d(idtySmpl.email),
                e2d(idtySmpl.location), e2d(idtySmpl.website)))
            // (Could fix: `returning ID into ?`, saves 1 roundtrip.)
            // Loop one more lap to read ID.
          }
        }
        assErrIf3(idtyId.isEmpty, "DwE3kRhk20")
        val notfPrefs: EmailNotfPrefs = _toEmailNotfs(emailNotfsStr)
        // Derive a temporary user from the identity, see
        // Debiki for Developers #9xdF21.
        val user = _dummyUserFor(identity = idtySmpl,
          emailNotfPrefs = notfPrefs, id = _dummyUserIdFor(idtyId))
        val identityWithId = idtySmpl.copy(id = idtyId, userId = user.id)
        // Quota already consumed (in the `for` loop above).
        val loginWithId = _saveLogin(loginReq.login, identityWithId)
        LoginGrant(loginWithId, identityWithId, user,
          isNewIdentity = createdNewIdty, isNewRole = false)
      }
    }

    // Handle guest/unauthenticated login and email login.
    loginReq.identity match {
      case idtySmpl: IdentitySimple =>
        val loginGrant = _loginUnauIdty(idtySmpl)
        return loginGrant

      case idty: IdentityEmailId =>
        val loginGrant = _loginWithEmailId(idty.id)
        return loginGrant

      case _ => ()
    }

    db.transaction { implicit connection =>

    // Load any matching Identity and the related User.
      val (identityInDb: Option[Identity], userInDb: Option[User]) =
        _loadIdtyDetailsAndUser(forIdentity = loginReq.identity)

      // Create user if absent.
      val user = userInDb match {
        case Some(u) => u
        case None =>
          // Copy identity name/email/etc fields to the new role.
          // Data in DW1_USERS has precedence over data in the DW1_IDS_*
          // tables, see Debiki for Developers #3bkqz5.
          val idty = loginReq.identity
          val userNoId =  User(id = "?", displayName = idty.displayName,
            email = idty.email, emailNotfPrefs = EmailNotfPrefs.Unspecified,
            country = "", website = "", isAdmin = false, isOwner = false)
          val userWithId = _insertUser(siteId, userNoId)
          userWithId
      }

      // Create or update the OpenID/Twitter/etc identity.
      //
      // (It's absent, if this is the first time the user logs in.
      // It needs to be updated, if the user has changed e.g. her
      // OpenID name or email. Or Facebook name or email.)
      //
      // (Concerning simultaneous inserts/updates by different threads or
      // server nodes: This insert might result in a unique key violation
      // error. Simply let the error propagate and the login fail.
      // This login was supposedly initiated by a human, and there is
      // no point in allowing exactly simultaneous logins by one
      // single human.)

      val identity = (identityInDb, loginReq.identity) match {
        case (None, newNoId: IdentityOpenId) =>
          _insertIdentity(siteId, newNoId.copy(userId = user.id))
        case (Some(old: IdentityOpenId), newNoId: IdentityOpenId) =>
          val nev = newNoId.copy(id = old.id, userId = user.id)
          if (nev != old) {
            if (nev.isGoogleLogin)
              assErrIf(nev.email != old.email || !old.isGoogleLogin, "DwE3Bz6")
            else
              assErrIf(nev.oidClaimedId != old.oidClaimedId, "DwE73YQ2")
            _updateIdentity(nev)
          }
          nev
        case (x, y) => assErr(
          "DwE8IR31", s"Mismatch: (${classNameOf(x)}, ${classNameOf(y)})")
      }

      val login = _saveLogin(loginReq.login, identity)

      LoginGrant(login, identity, user, isNewIdentity = identityInDb.isEmpty,
        isNewRole = userInDb.isEmpty)
    }
  }

}
