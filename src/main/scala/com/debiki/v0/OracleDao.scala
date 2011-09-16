/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.IntrsAllowed._
import com.debiki.v0.Dao._
import _root_.net.liftweb.common.{Loggable, Box, Empty, Full, Failure}
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import oracle.{jdbc => oj}
import oracle.jdbc.{pool => op}
import scala.collection.{mutable => mut}

object OracleDaoSpi {

  /** Returns a failure or a Full(dao). */
  def connectAndUpgradeSchema(
      connUrl: String, user: String, pswd: String): Box[OracleDaoSpi] = {
    try {
      Full(connectAndUpgradeSchemaThrow(connUrl, user, pswd))
    } catch {
      case ex: Exception => Failure(
        "Error creating DAO [debiki_error_8skeFQ2]", Full(ex), Empty)
    }
  }

  /** Returns a working DAO or throws an error. */
  def connectAndUpgradeSchemaThrow(
      connUrl: String, user: String, pswd: String): OracleDaoSpi = {
    // COULD catch connection errors, and return Failure.
    val oradb = new OracleDb(connUrl, user, pswd)  // can throw SQLException
    val schema = new OracleSchema(oradb)
    // COULD skip schema.upgrade(). Cannot really do the upgrade automatically,
    // because > 1 appserver might connect to the database at the same time
    // but only 1 appserver should do the upgrade. Also, if an older
    // appserver (that doesn't understand the new schema structure) uses
    // the schema, then another appserver with a higher version should not
    // upgrade the db. Not until the old appserver has been upgraded so it
    // will understand the schema, after the upgrade.
    // COULD skip upgrade() because it makes the setup(EmptySchema)
    // unit tests (in DaoTckTest) fail.
    val ups: Box[List[String]] = schema.upgrade()
    val curVer = OracleSchema.CurVersion
    ups match {
      case Full(`curVer`::_) => new OracleDaoSpi(schema)
      case Full(_) => assErr("[debiki_error_77Kce29h]")
      case Empty => assErr("[debiki_error_33k2kSE]")
      case f: Failure => throw f.exception.open_!
    }
  }
}


class OracleDaoSpi(val schema: OracleSchema) extends DaoSpi with Loggable {
  // COULD serialize access, per page?

  import OracleDb._

  def db = schema.oradb

  def close() { db.close() }

  def checkRepoVersion() = schema.readCurVersion()

  def secretSalt(): String = "9KsAyFqw_"

  def create(where: PagePath, debatePerhapsGuid: Debate): Box[Debate] = {
    var debate = if (debatePerhapsGuid.guid != "?") {
      unimplemented
      // Could use debatePerhapsGuid, instead of generatinig a new guid,
      // but i have to test that this works. But should where.guid.value
      // be Some or None? Would Some be the guid to reuse, or would Some
      // indicate that the page already exists, an error!?
    } else {
      debatePerhapsGuid.copy(guid = nextRandomString)  // TODO use the same
                                              // method in all DAO modules!
    }
    db.transaction { implicit connection =>
      _createPage(where, debate)
      val postsWithIds =
        _insert(where.tenantId, debate.guid, debate.posts).open_!
      Full(debate.copy(posts = postsWithIds))
    }
  }

  override def saveLogin(tenantId: String, loginReq: LoginRequest
                            ): LoginGrant = {

    // Assigns an id to `loginNoId', saves it and returns it (with id).
    def _saveLogin(loginNoId: Login, identityWithId: Identity)
                  (implicit connection: js.Connection): Login = {
      // Create a new _LOGINS row, pointing to identityWithId.
      require(loginNoId.id startsWith "?")
      require(loginNoId.identityId startsWith "?")
      val loginSno = db.nextSeqNo("DW1_LOGINS_SNO")
      val login = loginNoId.copy(id = loginSno.toString,
                                identityId = identityWithId.id)
      val identityType = identityWithId match {
        case _: IdentitySimple => "Simple"
        case _: IdentityOpenId => "OpenID"
        case _ => assErr("[debiki_error_03k2r21K5]")
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
          List(loginSno.asInstanceOf[AnyRef], tenantId,
              login.prevLoginId.getOrElse(""),  // UNTESTED unless empty
              login.identityId.toLong.asInstanceOf[AnyRef],
              login.ip, login.date))
      login
    }

    // Special case for IdentitySimple, because they map to no User.
    // Instead we create a "fake" User (that is not present in DW1_USERS)
    // and assign it id = -IdentitySimple.id (i.e. a leading dash)
    // and return it.
    //    I don't want to save IdentitySimple people in DW1_USERS, because
    // I think that'd make possible future security *bugs*, when privileges
    // are granted to IdentitySimple users, which don't even need to
    // specify any password to "login"!
    loginReq.identity match {
      case s: IdentitySimple =>
        val simpleLogin = db.transaction { implicit connection =>
          // Find or create _SIMPLE row.
          var identitySno: Option[Long] = None
          for (i <- 1 to 2 if identitySno.isEmpty) {
            db.query("""
                select SNO from DW1_IDS_SIMPLE
                where NAME = ? and EMAIL = ? and LOCATION = ? and
                      WEBSITE = ?""",
                List(e2d(s.name), e2d(s.email), e2d(s.location),
                    e2d(s.website)),
                rs => {
              if (rs.next) identitySno = Some(rs.getLong("SNO"))
              Empty // dummy
            })
            if (identitySno isEmpty) {
              // Create simple user info.
              // There is a unique constraint on NAME, EMAIL, LOCATION,
              // WEBSITE, so this insert might fail (if another thread does
              // the insert, just before). Should it fail, the above `select'
              // is run again and finds the row inserted by the other thread.
              // Could avoid logging any error though!
              db.update("""
                  insert into DW1_IDS_SIMPLE(
                      SNO, NAME, EMAIL, LOCATION, WEBSITE)
                  values (DW1_IDS_SNO.nextval, ?, ?, ?, ?)""",
                  List(s.name, e2d(s.email), e2d(s.location), e2d(s.website)))
                  // COULD fix: returning SNO into ?""", saves 1 roundtrip.
              // Loop one more lap to read SNO.
            }
          }
          assErrIf(identitySno.isEmpty, "[debiki_error_93kRhk20")
          val identityId = identitySno.get.toString
          val user = _dummyUserFor(identity = s,
                                  id = _dummyUserIdFor(identityId))
          val identityWithId = s.copy(id = identityId, userId = user.id)
          val loginWithId = _saveLogin(loginReq.login, identityWithId)
          Full(LoginGrant(loginWithId, identityWithId, user))
        }.open_!
        return simpleLogin
      case _ => ()
    }

    // Load the User, if any, and the OpenID/Twitter/Facebook identity id:
    // Construct a query that joins the relevant DW1_IDS_<id-type> table
    // with DW1_USERS.
    // COULD move this user loading code to _loadUsers. This code
    // loads a user and also its *identity* though.
    var sqlSelectList = """
        select
            u.SNO USER_SNO,
            u.DISPLAY_NAME,
            u.EMAIL,
            u.COUNTRY,
            u.WEBSITE,
            u.SUPERADMIN,
            i.SNO IDENTITY_SNO,
            i.USR,
            i.USR_ORIG,
            """

    // SQL for selecting User and Identity. Depends on the Identity type.
    val (templateUser, sqlFromWhere, bindVals) = loginReq.identity match {
      case oid: IdentityOpenId =>
        (User(id = "?",
              displayName = oid.firstName,
              email = oid.email,
              country = oid.country,
              website = "",
              isSuperAdmin = false),
        """ i.OID_OP_LOCAL_ID,
            i.OID_REALM,
            i.OID_ENDPOINT,
            i.OID_VERSION,
            i.FIRST_NAME,
            i.EMAIL,
            i.COUNTRY
          from DW1_IDS_OPENID i, DW1_USERS u
            -- in the future: could join with DW1_IDS_OPENID_ATRS,
            -- which would store the FIRST_NAME, EMAIL, COUNTRY
            -- that the OpenID provider sent, originally (but which the
            -- user might have since changed).
          where i.TENANT = ?
            and i.OID_CLAIMED_ID = ?
            and i.TENANT = u.TENANT -- (not needed, u.SNO is unique)
            and i.USR = u.SNO
            """,
          List(tenantId, oid.oidClaimedId)
        )
      // case fid: IdentityTwitter => (User, SQL for Twitter identity table)
      // case fid: IdentityFacebook => (User, ...)
      case _: IdentitySimple => assErr("[debiki_error_98239k2a2]")
      case IdentityUnknown => assErr("[debiki_error_92k2rI06]")
    }

    db.transaction { implicit connection =>
      // Load any matching Identity and the related User.
      val (identityInDb: Option[Identity], userInDb: Option[User]) =
          db.query(sqlSelectList + sqlFromWhere, bindVals, rs => {
        if (rs.next) {
          val userInDb = User(
              id = rs.getLong("USER_SNO").toString,
              displayName = n2e(rs.getString("DISPLAY_NAME")),
              email = n2e(rs.getString("EMAIL")),
              country = n2e(rs.getString("COUNTRY")),
              website = n2e(rs.getString("WEBSITE")),
              isSuperAdmin = rs.getString("SUPERADMIN") == "T")
          val identityInDb = loginReq.identity match {
            case iod: IdentityOpenId =>
              IdentityOpenId(
                id = rs.getLong("IDENTITY_SNO").toString,
                userId = userInDb.id,
                oidEndpoint = rs.getString("OID_ENDPOINT"),
                oidVersion = rs.getString("OID_VERSION"),
                oidRealm = rs.getString("OID_REALM"),
                oidClaimedId = iod.oidClaimedId,
                oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
                firstName = rs.getString("FIRST_NAME"),
                email = rs.getString("EMAIL"),
                country = rs.getString("COUNTRY"))
            // case _: IdentityTwitter =>
            // case _: IdentityFacebook =>
            case sid: IdentitySimple => assErr("[debiki_error_8451kx350]")
            case IdentityUnknown => assErr("[debiki_error_091563wkr21]")
          }
          Full(Some(identityInDb) -> Some(userInDb))
        } else {
          Full(None -> None)
        }
      }).open_!

      // Create user if absent.
      val user = userInDb match {
        case Some(u) => u
        case None =>
          val userSno = db.nextSeqNo("DW1_USERS_SNO")
          val u = templateUser.copy(id = userSno.toString)
          db.update("""
              insert into DW1_USERS(
                  TENANT, SNO, DISPLAY_NAME, EMAIL, COUNTRY)
              values (?, ?, ?, ?, ?)""",
              List(tenantId, userSno.asInstanceOf[AnyRef],
                  u.displayName, u.email, u.country))
          u
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

      val newIdentitySno: Option[Long] =
          if (identityInDb.isDefined) None
          else Some(db.nextSeqNo("DW1_IDS_SNO"))

      val identity = (identityInDb, loginReq.identity) match {
        case (None, newNoId: IdentityOpenId) =>
          // Assign id and create.
          val nev = newNoId.copy(id = newIdentitySno.get.toString,
                                 userId = user.id)
          db.update("""
              insert into DW1_IDS_OPENID(
                  SNO, TENANT, USR, USR_ORIG, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                  OID_REALM, OID_ENDPOINT, OID_VERSION,
                  FIRST_NAME, EMAIL, COUNTRY)
              values (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              List(newIdentitySno.get.asInstanceOf[AnyRef],
                  tenantId, nev.userId, nev.userId,
                  nev.oidClaimedId, e2d(nev.oidOpLocalId), e2d(nev.oidRealm),
                  e2d(nev.oidEndpoint), e2d(nev.oidVersion),
                  e2d(nev.firstName), e2d(nev.email), e2d(nev.country)))
          nev
        case (Some(old: IdentityOpenId), newNoId: IdentityOpenId) =>
          val nev = newNoId.copy(id = old.id, userId = user.id)
          if (nev != old) {
            db.update("""
                update DW1_IDS_OPENID set
                    USR = ?, OID_OP_LOCAL_ID = ?, OID_REALM = ?,
                    OID_ENDPOINT = ?, OID_VERSION = ?,
                    FIRST_NAME = ?, EMAIL = ?, COUNTRY = ?
                where SNO = ? and TENANT = ?
                """,
                List(nev.userId,  e2d(nev.oidOpLocalId), e2d(nev.oidRealm),
                  e2d(nev.oidEndpoint), e2d(nev.oidVersion),
                  e2d(nev.firstName), e2d(nev.email), e2d(nev.country),
                  nev.id, tenantId))//.toLong.asInstanceOf[AnyRef], tenantId))
          }
          nev
        // case (..., IdentityTwitter) => ...
        // case (..., IdentityFacebook) => ...
        case (_, _: IdentitySimple) => assErr("[debiki_error_83209qk12kt0]")
        case (_, IdentityUnknown) => assErr("[debiki_error_32ks30016")
      }

      val login = _saveLogin(loginReq.login, identity)

      Full(LoginGrant(login, identity, user))
    }.open_!  // pointless box
  }

  override def saveLogout(loginId: String, logoutIp: String) {
    db.transaction { implicit connection =>
      db.update("""
          update DW1_LOGINS set LOGOUT_IP = ?, LOGOUT_TIME = ?
          where SNO = ?""", List(logoutIp, new ju.Date, loginId)) match {
        case Full(1) => Empty  // ok
        case Full(x) => assErr("Updated "+ x +" rows [debiki_error_03k21rx1]")
        case badBox => unimplemented // remove boxes
      }
    }
  }

  private def _loadUsers(onPageWithSno: Long = -1,
                         withLoginId: String = null,
                         tenantId: String
                            ): Pair[List[Identity], List[User]] = {
    // Load users. First find all relevant identities, by joining
    // DW1_PAGE_ACTIONS and _LOGINS. Then all user ids, by joining
    // the result with _IDS_SIMPLE and _IDS_OPENID. Then load the users.

    require((onPageWithSno == -1) ^ (withLoginId eq null))
    val (selectLoginIds, args) = (onPageWithSno, withLoginId) match {
      case (-1, loginId) => ("""
          select ID_SNO, ID_TYPE
              from DW1_LOGINS
              where SNO = ?
          """, List(loginId)) // why not needed: .toLong.asInstanceOf[AnyRef]?
      case (pageSno, null) => ("""
          select distinct l.ID_SNO, l.ID_TYPE
              from DW1_PAGE_ACTIONS a, DW1_LOGINS l
              where a.PAGE = ? and a.LOGIN = l.SNO
          """, List(pageSno.asInstanceOf[AnyRef]))
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
            select ID_TYPE, s.SNO I_ID, 0 I_USR,
                   s.NAME I_NAME, s.EMAIL I_EMAIL,
                   s.LOCATION I_WHERE, s.WEBSITE I_WEBSITE
            from DW1_IDS_SIMPLE s, logins
            where s.SNO = logins.ID_SNO and logins.ID_TYPE = 'Simple'
            union
            -- OpenID
            select ID_TYPE, oi.SNO I_ID, oi.USR,
                   oi.FIRST_NAME I_NAME, oi.EMAIL I_EMAIL,
                   oi.COUNTRY I_WHERE, cast('' as nvarchar2(100)) I_WEBSITE
            from DW1_IDS_OPENID oi, logins
            where oi.SNO = logins.ID_SNO and logins.ID_TYPE = 'OpenID'
            -- union
            -- Twitter tables
            -- Facebook tables
            )
        select i.ID_TYPE, i.I_ID,
            i.I_NAME, i.I_EMAIL, i.I_WHERE, i.I_WEBSITE,
            u.SNO U_ID,
            u.DISPLAY_NAME U_DISP_NAME,
            u.EMAIL U_EMAIL,
            u.COUNTRY U_COUNTRY,
            u.WEBSITE U_WEBSITE,
            u.SUPERADMIN U_SUPERADMIN
        from identities i, DW1_USERS u
        where u.SNO(+) = i.I_USR
          and u.TENANT(+) = ?
        """, args ::: List(tenantId), rs => {
      var usersById = mut.HashMap[String, User]()
      var identities = List[Identity]()
      while (rs.next) {
        val idId = rs.getString("I_ID")
        val userId = rs.getLong("U_ID").toString
        assErrIf(idId isEmpty, "[debiki_error_392Qvc89]")

        if (!usersById.contains(userId)) usersById(userId) = User(
            id = userId,
            displayName = n2e(rs.getString("U_DISP_NAME")),
            email = n2e(rs.getString("U_EMAIL")),
            country = n2e(rs.getString("U_COUNTRY")),
            website = n2e(rs.getString("U_WEBSITE")),
            isSuperAdmin = rs.getString("U_SUPERADMIN") == "T")

        identities ::= (rs.getString("ID_TYPE") match {
          case "Simple" => {
            IdentitySimple(
                id = idId,
                userId = userId,
                name = n2e(rs.getString("I_NAME")),
                email = n2e(rs.getString("I_EMAIL")),
                location = n2e(rs.getString("I_WHERE")),
                website = n2e(rs.getString("I_WEBSITE")))
          }
          case "OpenID" => {
            assErrIf(userId isEmpty, "[debiki_error_9V86krR3]")
            IdentityOpenId(
                id = idId,
                userId = userId,
                // These uninteresting OpenID fields were never loaded.
                // COULD place them in an Option[OpenIdInfo]?
                oidEndpoint = "?",
                oidVersion = "?",
                oidRealm = "?",
                oidClaimedId = "?",
                oidOpLocalId = "?",
                firstName = n2e(rs.getString("I_NAME")),
                email = n2e(rs.getString("I_EMAIL")),
                country = n2e(rs.getString("I_WHERE")))
          }
        })
      }
      Full((identities,
          usersById.values.toList))  // silly to throw away hash map
    }).open_! // silly box
  }

  override def save[T](tenantId: String, pageGuid: String, xs: List[T]
                          ): Box[List[T]] = {
    db.transaction { implicit connection =>
      _insert(tenantId, pageGuid, xs)
    }
  }

  def load(tenantId: String, pageGuid: String): Box[Debate] = {
    /*
    db.transaction { implicit connection =>

      // Prevent phantom reads from DW1_ACTIONS. (E.g. rating tags are read
      // from DW1_RATINGS before _ACTIONS is considered, and another session
      // might insert a row into _ACTIONS just after _RATINGS was queried.)
      connection.setTransactionIsolation(
        Connection.TRANSACTION_SERIALIZABLE)
      */

    var pageSno: Long = -1
    var logins = List[Login]()
    var identities = List[Identity]()
    var users = List[User]()

    // Load all logins for pageGuid. Load DW1_PAGES.SNO at the same time
    // (although it's then included on each row) to avoid db roundtrips.
    db.queryAtnms("""
        select p.SNO PAGE_SNO,
            l.SNO LOGIN_SNO, l.PREV_LOGIN,
            l.ID_TYPE, l.ID_SNO,
            l.LOGIN_IP, l.LOGIN_TIME,
            l.LOGOUT_IP, l.LOGOUT_TIME
        from DW1_TENANTS t, DW1_PAGES p, DW1_PAGE_ACTIONS a, DW1_LOGINS l
        where t.ID = ?
          and p.TENANT = t.ID
          and p.GUID = ?
          and a.PAGE = p.SNO
          and a.LOGIN = l.SNO""", List(tenantId, pageGuid), rs => {
      while (rs.next) {
        pageSno = rs.getLong("PAGE_SNO")
        val loginId = rs.getLong("LOGIN_SNO").toString
        val prevLogin = Option(rs.getLong("PREV_LOGIN")).map(_.toString)
        val ip = rs.getString("LOGIN_IP")
        val date = rs.getDate("LOGIN_TIME")
        // ID_TYPE need not be remembered, since each ID_SNO value
        // is unique over all DW1_LOGIN_OPENID/SIMPLE/... tables.
        // (So you'd find the appropriate IdentitySimple/OpenId by doing
        // People.identities.find(_.id = x).)
        val idId = rs.getLong("ID_SNO").toString
        logins ::= Login(id = loginId, prevLoginId = prevLogin, ip = ip,
                        date = date, identityId = idId)
      }
      Empty
    })

    // Load simple logins and construct dummy users.
    db.queryAtnms("""
        select s.SNO, s.NAME, s.EMAIL, s.LOCATION, s.WEBSITE
        from DW1_PAGE_ACTIONS a, DW1_LOGINS l, DW1_IDS_SIMPLE s
        where a.PAGE = ?
          and a.LOGIN = l.SNO
          and l.ID_TYPE = 'Simple'
          and l.ID_SNO = s.SNO """,
        List(pageSno.toString), rs => {
      while (rs.next) {
        val sno = rs.getLong("SNO").toString
        val name = d2e(rs.getString("NAME"))
        val email = d2e(rs.getString("EMAIL"))
        val location = d2e(rs.getString("LOCATION"))
        val website = d2e(rs.getString("WEBSITE"))
        val identity =  IdentitySimple(id = sno, userId = _dummyUserIdFor(sno),
            name = name, email = email, location = location, website = website)
        identities ::= identity
        users ::= _dummyUserFor(identity)  // there's no DW1_USERS row to load
      }
      Empty
    })

    // Load OpenID logins.
    // COULD merge with _loadUsers?
    // COULD split IdentityOpenId into IdentityOpenId and IdentityOpenIdDetails
    // -- the latter would contain e.g. endpoint and op-local-id and such.
    // And not load all details here.
    db.queryAtnms("""
        select o.SNO, o.USR, o.OID_ENDPOINT, o.OID_VERSION, o.OID_REALM,
              o.OID_CLAIMED_ID, o.OID_OP_LOCAL_ID, o.FIRST_NAME,
              o.EMAIL, o.COUNTRY
        from DW1_PAGE_ACTIONS a, DW1_LOGINS l, DW1_IDS_OPENID o
        where a.PAGE = ?
          and a.LOGIN = l.SNO
          and l.ID_TYPE = 'OpenID'
          and l.ID_SNO = o.SNO
          and o.TENANT = ?""",
        List(pageSno.toString, tenantId), rs => {
      while (rs.next) {
        identities ::= IdentityOpenId(
            id = rs.getLong("SNO").toString,
            userId = rs.getLong("USR").toString,
            oidEndpoint = rs.getString("OID_ENDPOINT"),
            oidVersion = rs.getString("OID_VERSION"),
            oidRealm = rs.getString("OID_REALM"),
            oidClaimedId = rs.getString("OID_CLAIMED_ID"),
            oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
            firstName = rs.getString("FIRST_NAME"),
            email = rs.getString("EMAIL"),
            country = rs.getString("COUNTRY"))
      }
      Empty
    })

    // Load users.  COULD use _loadUsers instead above too, to load identitis?
    val (identitiesIgnored, moreUsers) = _loadUsers(onPageWithSno = pageSno,
                                                    tenantId = tenantId)
    users :::= moreUsers

    // Load rating tags.
    val ratingTags: mut.HashMap[String, List[String]] = db.queryAtnms("""
        select a.PAID, r.TAG from DW1_PAGE_ACTIONS a, DW1_PAGE_RATINGS r
        where a.TYPE = 'Rating' and a.PAGE = ? and a.PAGE = r.PAGE and
              a.PAID = r.PAID
        order by a.PAID
        """,
      List(pageSno.toString), rs => {
        val map = mut.HashMap[String, List[String]]()
        var tags = List[String]()
        var curPaid = ""  // current page action id

        while (rs.next) {
          val paid = rs.getString("PAID");
          val tag = rs.getString("TAG");
          if (curPaid isEmpty) curPaid = paid
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

        Full(map)
      }).open_!  // COULD throw exceptions, don't use boxes?

    // Order by TIME desc, because when the action list is constructed
    // the order is reversed again.
    db.queryAtnms("""
        select PAID, LOGIN, TIME, TYPE, RELPA,
              TEXT, MARKUP, WHEERE, NEW_IP, DESCR
        from DW1_PAGE_ACTIONS
        where PAGE = ?
        order by TIME desc
        """,
        List(pageSno.toString), rs => {
      var actions = List[AnyRef]()
      while (rs.next) {
        val id = rs.getString("PAID");
        val loginSno = rs.getLong("LOGIN").toString
        val time = ts2d(rs.getTimestamp("TIME"))
        val typee = rs.getString("TYPE");
        val relpa = rs.getString("RELPA")
        val text_? = rs.getString("TEXT")
        val markup_? = rs.getString("MARKUP")
        val where_? = rs.getString("WHEERE")
        val newIp = Option(rs.getString("NEW_IP"))
        val desc_? = rs.getString("DESCR")

        val action = typee match {
          case "Post" =>
            // How repr empty root post parent? ' ' or '-' or '_' or '0'?
            new Post(id = id, parent = relpa, date = time,
              loginId = loginSno, newIp = newIp, text = n2e(text_?),
              markup = n2e(markup_?), where = Option(where_?))
          case "Rating" =>
            val tags = ratingTags(id)
            new Rating(id = id, postId = relpa, date = time,
              loginId = loginSno, newIp = newIp, tags = tags)
          case "Edit" =>
            new Edit(id = id, postId = relpa, date = time,
              loginId = loginSno, newIp = newIp, text = n2e(text_?),
              desc = n2e(desc_?))
          case "EditApp" =>
            new EditApplied(editId = relpa, date = time,
              loginId = loginSno, //newIp = newIp,
              result = n2e(text_?), debug = n2e(desc_?))
          case x => return Failure(
              "Bad DW1_ACTIONS.TYPE: "+ safed(typee) +" [debiki_error_Y8k3B]")
        }
        actions ::= action  // this reverses above `order by TIME desc'
      }

      Full(Debate.fromActions(guid = pageGuid,
          logins, identities, users, actions))
    })
  }

  def loadTemplates(perhapsTmpls: List[PagePath]): List[Debate] = {
    // TODO: TRANSACTION_SERIALIZABLE, or someone might delete
    // a template after its guid has been looked up.
    if (perhapsTmpls isEmpty) return Nil
    val tenantId = perhapsTmpls.head.tenantId
    // For now, do 1 lookup per location.
    // Minor bug: if template /some-page.tmpl does not exist, but there's
    // an odd page /some-page.tmpl/, then that *page* is found and
    // returned although it's probably not a template.  ? Solution:
    // TODO disallow '.' in directory names? but allow e.g. style.css,
    // scripts.js, template.tpl.
    var guids = List[String]()
    perhapsTmpls map { tmplPath =>
      assert(tmplPath.tenantId == tenantId)
      _findCorrectPagePath(tmplPath) match {
        case Full(pagePath) => guids ::= pagePath.guid.value.get
        case Empty => // fine, template does not exist
        case f: Failure => error(
          "Error loading template guid [debiki_error_309sU32]:\n"+ f)
      }
    }
    val pages: List[Debate] = guids.reverse map { guid =>
      load(tenantId, guid) match {
        case Full(d: Debate) => d
        case x =>
          // Database inaccessible? If I fixed TRANSACTION_SERIALIZABLE
          // (see above) the template would have been found for sure.
          val err = "Error loading template [debiki_error_983keCK31]"
          logger.error(err +":"+ x.toString) // COULD fix consistent err reprt
          error(err)
      }
    }
    pages
  }

  def createTenant(name: String): Tenant = {
    db.transaction { implicit connection =>
      val tenantId = db.nextSeqNo("DW1_TENANTS_ID").toString
      db.update("""
          insert into DW1_TENANTS (ID, NAME)
          values (?, ?)
          """, List(tenantId, name))
      Full(Tenant(id = tenantId, name = name, hosts = Nil))
    }.open_!
  }

  def addTenantHost(tenantId: String, host: TenantHost) = {
    db.transaction { implicit connection =>
      val cncl = if (host.isCanonical) "T" else "F"
      val https = host.https match {
        case TenantHost.HttpsRequired => "Required"
        case TenantHost.HttpsAllowed => "Allowed"
        case TenantHost.HttpsNone => "No"
      }
      db.update("""
          insert into DW1_TENANT_HOSTS (TENANT, HOST, CANONICAL, HTTPS)
          values (?, ?, ?, ?)
          """,
          List(tenantId, host.address, cncl, https))
    }
  }

  def lookupTenant(scheme: String, host: String): TenantLookup = {
    db.queryAtnms("""
        select t.TENANT TID,
            t.CANONICAL THIS_CANONICAL, t.HTTPS THIS_HTTPS,
            c.HOST CANONICAL_HOST, c.HTTPS CANONICAL_HTTPS
        from DW1_TENANT_HOSTS t, -- this host, the one connected to
            DW1_TENANT_HOSTS c  -- the cannonical host
        where t.HOST = ?
          and c.TENANT = t.TENANT
          and c.CANONICAL = 'T'
        """, List(host), rs => {
      if (!rs.next) return FoundNothing
      val tenantId = rs.getString("TID")
      val thisIsChost = rs.getString("THIS_CANONICAL") == "T"
      val thisHttps = rs.getString("THIS_HTTPS")
      val chost = rs.getString("CANONICAL_HOST")
      val chostHttps = rs.getString("CANONICAL_HTTPS")
      def chostUrl =  // the canonical host URL, e.g. http://www.example.com
          (if (chostHttps == "Required") "https://" else "http://") + chost
      assErrIf(thisIsChost != (host == chost), "[debiki_error_98h2kwi1215]")

      def useThisHostAndScheme = FoundChost(tenantId)
      def redirect = FoundAlias(tenantId, canonicalHostUrl = chostUrl,
                              shouldRedirect = true)
      def useLinkRelCanonical = redirect.copy(shouldRedirect = false)

      Full((thisIsChost, scheme, thisHttps) match {
        case (true, "http" , "Required") => redirect
        case (true, "http" , _         ) => useThisHostAndScheme
        case (true, "https", "Required") => useThisHostAndScheme
        case (true, "https", "Allowed" ) => useLinkRelCanonical
        case (true, "https", "No"      ) => redirect
        case (false, _     , _         ) => redirect
      })
    }).open_!
  }

  def checkPagePath(pathToCheck: PagePath): Box[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def checkAccess(pagePath: PagePath, loginId: Option[String], doo: Do
                 ): (Option[Identity], Option[User], Option[IntrsAllowed]) = {
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

     val (identity: Option[Identity], user: Option[User]
         ) = loginId map { id =>
      _loadUsers(withLoginId = id, tenantId = pagePath.tenantId
                ) match {
        case (List(i: Identity), List(u: User)) => (Some(i), Some(u))
        case (List(i: IdentitySimple), Nil) => (Some(i), Some(_dummyUserFor(i)))
        case (List(i: Identity), Nil) => assErr(
            "Found no user for login id "+ safed(id) +
            " with identity "+ safed(i.id) +" [debiki_error_6349krq20]")
        case (Nil, Nil) =>
          // The webapp should never try to load non existing identities?
          // (The login id was once fetched from the database.
          // It is sent to the client in a signed cookie so it cannot be
          // tampered with.) Identities cannot be deleted!
          logger.warn("Found no identity for login id "+ safed(id) +
                    " [debiki_error_743xdk15]")
          return (None, None, None)  // deny everything
        case (is, us) =>
          // There should be exactly one identity per login, and at most
          // one user per identity.
          assErr("Found "+ is.length +" identities and "+ us.length +
              " users for login id " + safed(id) + " [debiki_error_42RxkW1]")
      }
    } getOrElse (None, None)

    // Allow superadmins to do anything, e.g. create pages anywhere.
    // (Currently users can edit their own pages only.)
    if (user.map(_.isSuperAdmin) == Some(true))
      return (identity, user,
          Some(VisibleTalk)) // weird name, should redesign permission system

    // For now, hide .js and .css and .tmpl files for everyone but superadmins.
    // (If people can *edit* them, they could conduct xss attacks.)
    if (pagePath.name.contains('.'))
      return (identity, user, None)

    // Non-admins can only create pages whose names are prefixed
    // with their guid, like so: /folder/-guid-pagename
    // (Currently there are no admin users, only superadmins)
    (doo, pagePath.guid) match {
      case (Do.Create, GuidInPath(_)) => ()
      case (Do.Create, GuidHidden(_)) => return (identity, user, None)
      case (Do.Create, GuidUnknown) => assErr("[debiki_error_0128krRi315]")
      case _ => ()
    }

    val intrsOk: IntrsAllowed = {
      val p = pagePath.path
      if (p == "/test/") VisibleTalk
      else if (p == "/allt/") VisibleTalk
      else if (p == "/forum/") VisibleTalk
      else if (doo == Do.Create) {
        // Don't allow new pages in the below paths.
        return  (identity, user, None)
      }
      else if (p == "/blogg/") VisibleTalk
      else if (p == "/arkiv/") VisibleTalk
      else HiddenTalk
    }
    (identity, user, Some(intrsOk))
  }

  // Looks up the correct PagePath for a possibly incorrect PagePath.
  private def _findCorrectPagePath(pagePathIn: PagePath): Box[PagePath] = {
    var query = """
        select FOLDER, PAGE_GUID, PAGE_NAME, GUID_IN_PATH
        from DW1_PATHS
        where TENANT = ?
        """
    var binds = List(pagePathIn.tenantId)
    var maxRowsFound = 1  // there's a unique key
    pagePathIn.guid.value match {
      case Some(guid) =>
        query += " and PAGE_GUID = ?"
        binds ::= guid
      case None =>
        // GUID_IN_PATH = 'F' means that the page guid must not be part
        // of the page url. ((So you cannot look up [a page that has its guid
        // as part of its url] by searching for its url without including
        // the guid. Had that been possible, many pages could have been found
        // since pages with different guids can have the same name.
        // Hmm, could search for all pages, as if the guid hadn't been
        // part of their name, and list all pages with matching names?))
        query += """
            and GUID_IN_PATH = 'F'
            and (
              (FOLDER = ? and PAGE_NAME = ?)
            """
        binds ::= pagePathIn.parent
        binds ::= e2s(pagePathIn.name)
        // Try to correct bad URL links.
        // COULD skip (some of) the two if tests below, if action is ?newpage.
        // (Otherwise you won't be able to create a page in
        // /some/path/ if /some/path already exists.)
        if (pagePathIn.name nonEmpty) {
          // Perhaps the correct path is /folder/page/ not /folder/page.
          // Try that path too. Choose sort orter so /folder/page appears
          // first, and skip /folder/page/ if /folder/page is found.
          query += """
              or (FOLDER = ? and PAGE_NAME = ' ')
              )
            order by length(FOLDER) asc
            """
          binds ::= pagePathIn.parent + pagePathIn.name +"/"
          maxRowsFound = 2
        }
        else if (pagePathIn.parent.count(_ == '/') >= 2) {
          // Perhaps the correct path is /folder/page not /folder/page/.
          // But prefer /folder/page/ if both pages are found.
          query += """
              or (FOLDER = ? and PAGE_NAME = ?)
              )
            order by length(FOLDER) desc
            """
          val perhapsPath = pagePathIn.parent.dropRight(1)  // drop `/'
          val lastSlash = perhapsPath.lastIndexOf("/")
          val (shorterPath, nonEmptyName) = perhapsPath.splitAt(lastSlash + 1)
          binds ::= shorterPath
          binds ::= nonEmptyName
          maxRowsFound = 2
        }
        else {
          query += ")"
        }
    }
    db.queryAtnms(query, binds.reverse, rs => {
      var correctPath: Box[PagePath] = Empty
      if (rs.next) {
        val guidVal = rs.getString("PAGE_GUID")
        val guid =
            if (rs.getString("GUID_IN_PATH") == "F") GuidHidden(guidVal)
            else GuidInPath(guidVal)
        correctPath = Full(pagePathIn.copy(  // keep pagePathIn.tenantId
            parent = rs.getString("FOLDER"),
            guid = guid,
            // If there is a root page ("serveraddr/") with no name,
            // it is stored as a single space; s2e removes such a space:
            name = s2e(rs.getString("PAGE_NAME"))))
      }
      assert(maxRowsFound == 2 || !rs.next)
      correctPath
    })
  }

  private def _createPage[T](where: PagePath, debate: Debate)
                            (implicit conn: js.Connection): Box[Int] = {
    db.update("""
        insert into DW1_PAGES (SNO, TENANT, GUID)
        values (DW1_PAGES_SNO.nextval, ?, ?)
        """, List(where.tenantId, debate.guid))

    // Concerning prefixing the page name with the page guid:
    // /folder/?createpage results in the guid prefixing the page name, like so:
    //    /folder/-guid-pagename
    // but /folder/pagename?createpage results in the guid being hidden,
    // and this'll be the path to the page:
    //    /folder/pagename
    val guidInPath = if (where.isFolderPath) "T" else "F"

    db.update("""
        insert into DW1_PATHS (TENANT, FOLDER, PAGE_GUID,
                                   PAGE_NAME, GUID_IN_PATH)
        values (?, ?, ?, ?, ?)
        """,
        List(where.tenantId, where.parent, debate.guid, e2s(where.name),
            guidInPath))
  }

  private def _insert[T](
        tenantId: String, pageGuid: String, xs: List[T])
        (implicit conn: js.Connection): Box[List[T]] = {
    var xsWithIds = (new Debate("dummy")).assignIdTo(
                      xs.asInstanceOf[List[AnyRef]]).asInstanceOf[List[T]]
                          // COULD make `assignIdTo' member of object Debiki$
    var bindPos = 0
    for (x <- xsWithIds) {
      // Could optimize:  (but really *not* important!)
      // Use a CallableStatement and `insert into ... returning ...'
      // to create the _ACTIONS row and read the SNO in one single roundtrip.
      // Or select many SNO:s in one single query? and then do a batch
      // insert into _ACTIONS (instead of one insert per row), and then
      // batch inserts into other tables e.g. _RATINGS.
      // Also don't need to select pageSno every time -- but probably better to
      // save 1 roundtrip: usually only 1 row is inserted at a time (an end
      // user posts 1 reply at a time).
      val pageSno = db.query("""
          select SNO from DW1_PAGES
            where TENANT = ? and GUID = ?
          """, List(tenantId, pageGuid), rs => {
        rs.next
        Full(rs.getLong("SNO").toString)
      }).open_!

      val insertIntoActions = """
          insert into DW1_PAGE_ACTIONS(LOGIN, PAGE, PAID, TIME, TYPE,
                                    RELPA, TEXT, MARKUP, WHEERE, DESCR)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """
      // Keep in mind that Oracle converts "" to null.
      val commonVals = Nil // p.loginId::pageSno::Nil
      x match {
        case p: Post =>
          val markup = "" // TODO
          db.update(insertIntoActions, commonVals:::List(
            p.loginId, pageSno, p.id, p.date, "Post",
            p.parent, p.text, markup,
            p.where.getOrElse(Null(js.Types.NVARCHAR)), ""))
        case r: Rating =>
          db.update(insertIntoActions, commonVals:::List(
            r.loginId, pageSno, r.id, r.date, "Rating", r.postId,
            "", "", "", ""))
          db.batchUpdate("""
            insert into DW1_PAGE_RATINGS(PAGE, PAID, TAG) values (?, ?, ?)
            """, r.tags.map(t => List(pageSno, r.id, t)))
        case e: Edit =>
          db.update(insertIntoActions, commonVals:::List(
            e.loginId, pageSno, e.id, e.date, "Edit",
            e.postId, e.text, "", "", e.desc))
        case a: EditApplied =>
          val id = nextRandomString()  ; TODO // guid field
          db.update(insertIntoActions, commonVals:::List(
            a.loginId, pageSno, id, a.date, "EditApp",
            a.editId, a.result, "", "", ""))
        case x => unimplemented(
          "Saving this: "+ classNameOf(x) +" [debiki_error_38rkRF]")
      }
    }
    Full(xsWithIds)
  }

  def _dummyUserIdFor(identityId: String) = "-"+ identityId

  def _dummyUserFor(identity: IdentitySimple, id: String = null): User = {
    User(id = (if (id ne null) id else identity.userId),
      displayName = identity.name, email = identity.email, country = "",
      website = identity.website, isSuperAdmin = false)
  }

  // Builds a query that finds the user's current DW1_LOGIN.SNO.
  // If another thread has just logged the user out, the SNO for the
  // most recent 'LogIn' or 'NewIP' entry is returned.
  // COULD cache SNO in signed cookie instead? -- yes, now fixed, cached.
  /*
  def _findLoginSnoQuery(user: User): Pair[String, List[AnyRef]] = user match {
    case u @ UserSimple => ("""
      select max(l.SNO) LOGIN_SNO from DW1_LOGINS l, DW1_USERS_SIMPLE s
      where l.USER_TYPE = 'Simple' and l.WHAT in ('LogIn', 'NewIP')
        and l.USER_SNO = s.SNO
        and s.NAME = ? and s.EMAIL = ? and s.LOCATION = ? and s.WEBSITE = ?
      """, u.name::u.email::u.location::u.website::Nil)
    case u @ UserOpenID => ("""
      select max(l.SNO) LOGIN_SNO from DW1_LOGINS s, DW1_USERS_OPENID o
      where l.USER_TYPE = 'OpenID' and l.WHAT in ('LogIn', 'NewIP')
        and l.USER_SNO = o.SNO
        and o.PROVIDER = ? and o.REALM = ? and o.OID = ?
        and o.FIRST_NAME = ? and o.EMAIL = ? and o.COUNTRY = ?
      """, u.provider::u.realm::u.oid::u.firstName::u.email::u.country::Nil)
    case u @ UserSystem =>
      ("select 0 LOGIN_SNO from dual", Nil)  // the system user has ID 0
        // TODO create system user row at startup if absent?
    case x => unimplemented(
      "Finding user type: "+ classNameOf(x) +" [debiki_error_0953kr2115]")
  } */

  /** Adds a can be Empty Prefix.
   *
   * Oracle converts the empty string to NULL, so prefix strings that might
   * be empty with a magic value, and remove it when reading data from
   * the db.
   */
  //private def _aep(str: String) = "-"+ str

  /** Removes a can be Empty Prefix. */
  //private def _rep(str: String) = str drop 1

  // TODO:
  /*
  From http://www.exampledepot.com/egs/java.sql/GetSqlWarnings.html:

    // Get warnings on Connection object
    SQLWarning warning = connection.getWarnings();
    while (warning != null) {
        // Process connection warning
        // For information on these values, see Handling a SQL Exception
        String message = warning.getMessage();
        String sqlState = warning.getSQLState();
        int errorCode = warning.getErrorCode();
        warning = warning.getNextWarning();
    }

    // After a statement has been used:
    // Get warnings on Statement object
    warning = stmt.getWarnings();
    if (warning != null) {
        // Process statement warnings...
    }

  From http://www.exampledepot.com/egs/java.sql/GetSqlException.html:
  
    try {
        // Execute SQL statements...
    } catch (SQLException e) {
        while (e != null) {
            // Retrieve a human-readable message identifying the reason
            // for the exception
            String message = e.getMessage();

            // This vendor-independent string contains a code that identifies
            // the reason for the exception.
            // The code follows the Open Group SQL conventions.
            String sqlState = e.getSQLState();

            // Retrieve a vendor-specific code identifying the reason for
            // the  exception.
            int errorCode = e.getErrorCode();

            // If it is necessary to execute code based on this error code,
            // you should ensure that the expected driver is being
            // used before using the error code.

            // Get driver name
            String driverName = connection.getMetaData().getDriverName();
            if (driverName.equals("Oracle JDBC Driver") && errorCode == 123) {
                // Process error...
            }

            // The exception may have been chained; process the next
            // chained exception
            e = e.getNextException();
        }
    }
   */
}

/*
class AllStatements(con: js.Connection) {
  con.setAutoCommit(false)
  con.setAutoCommit(true)
  // "It is advisable to disable the auto-commit mode only during th
  // transaction mode. This way, you avoid holding database locks for
  // multiple statements, which increases the likelihood of conflicts
  // with other users."
  // <http://download.oracle.com/javase/tutorial/jdbc/basics/transactions.html>



  val loadPage = con.prepareStatement("""
select 1 from dual
""")
  // ResultSet = updateSales.executeUpdate()
  // <an int value that indicates how many rows of a table were updated>
  //  = updateSales.executeQuery()
  // (DDL statements return 0)
  // // con.commit()
  // con.setAutoCommit(false)

  val purgePage = con.prepareStatement("""
select 1 from dual
""")

  val loadUser = con.prepareStatement("""
select 1 from dual
""")
}
*/

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
