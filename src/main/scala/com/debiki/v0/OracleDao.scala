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

  override def saveLogin(tenantId: String, loginStuff: LoginStuff
                            ): LoggedInStuff = {

    // Assigns an id to `login', saves it and returns it (with id assigned).
    def _saveLogin(loginNoId: Login, identityType: String)
                  (implicit connection: js.Connection): Login = {
      // Create a new _LOGINS row, pointing to the relevant
      // DW1_IDS_<identity-type> table.
      require(loginNoId.id startsWith "?")
      val loginSno = db.nextSeqNo("DW1_LOGINS_SNO")
      val login = loginNoId.copy(id = loginSno.toString)
      // (Need not bind `identityType'. It's not user supplied.
      // Binding it could make it harder for the opimizer
      // to find good plans.)
      assErrIf(identityType.contains("'"), "[debiki_error_093kr112435]")
      db.update("""
          insert into DW1_LOGINS(
              SNO, TENANT, PREV_LOGIN, ID_TYPE, ID_SNO,
              LOGIN_IP, LOGIN_TIME)
          values (?, (select SNO from DW1_TENANTS where NAME = ?), ?,
              '"""+ identityType +"', ?, ?, ?)",  // need not bind
      List(loginSno.asInstanceOf[AnyRef], tenantId,
          login.prevLoginId.getOrElse(""),  // for now
          login.identityId.toLong.asInstanceOf[AnyRef], login.ip, login.date))
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
    loginStuff.identity match {
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
          val loginNoId = loginStuff.login.copy(identityId = identityId)
          val loginWithId = _saveLogin(loginNoId, "Simple")
          Full(LoggedInStuff(loginWithId, identityWithId, user))
        }.open_!
        return simpleLogin
      case _ => ()
    }

    // Load the User, if any, and the OpenID/Twitter/Facebook identity id:
    // Construct a query that joins the relevant DW1_IDS_<id-type> table
    // with DW1_USERS.

    var sqlSelectList = """
        select
            u.SNO USER_SNO,
            u.DISPLAY_NAME,
            u.EMAIL,
            u.COUNTRY,
            u.WEBSITE,
            u.SUPERADMIN,
            i.SNO IDENTITY_SNO """

    // The User:s fields and the table join SQL and the values to bind
    // depends on the Identity type.
    val (templateUser, sqlFromWhere, bindVals) = loginStuff.identity match {
      case oid: IdentityOpenId =>
        // Find a matching DW1_IDS_OPENID row.
        // If there is none, create one, and a new _USERS row
        // (i.e. create a new user!).
        // Create a _LOGINS row that points to _OPENID.

        // Find an exactly matching _OPENID row.
        // Also find any row that matches the tenant and the claimend ID,
        // and remember the user ID from that row. (It's the same for all
        // rows with the same tenant and claimed ID, the table is
        // denormalized.)
        (User(id = "?",
              displayName = oid.firstName,
              email = oid.email,
              country = oid.country,
              website = "",
              isSuperAdmin = false),
        """
          from DW1_IDS_OPENID o2u,
             DW1_USERS u,
             DW1_IDS_OPENID i
          where
            o2u.TENANT = (
                  select SNO from DW1_TENANTS where NAME = ?)
            and o2u.OID_CLAIMED_ID = ?
            -- There's always a `o2u' row, if user has logged in before.
            -- So we'll be able to lookup user details.
            and o2u.TENANT = u.TENANT -- (not needed, u.SNO is unique)
            and o2u.USR = u.SNO
            -- There might be many rows matching the specified tenant
            -- and claimed id, but we only need one, since USR is the
            -- same for all those rows. (The table is denormalized.)
            and rownum <= 1
            -- There is zero or one `i. row that matches all OpenID
            -- attributes. If there is none, we'll create one (later).
            and o2u.TENANT = i.TENANT(+)
            and o2u.OID_CLAIMED_ID = i.OID_CLAIMED_ID(+)
            and i.OID_OP_LOCAL_ID(+) = ?
            and i.OID_REALM(+) = ?
            and i.OID_ENDPOINT(+) = ?
            and i.OID_VERSION(+) = ?
            and i.FIRST_NAME(+) = ?
            and i.EMAIL(+) = ?
            and i.COUNTRY(+) = ?
            """,
          List(tenantId, oid.oidClaimedId, e2d(oid.oidOpLocalId),
              e2d(oid.oidRealm), e2d(oid.oidEndpoint), e2d(oid.oidVersion),
              e2d(oid.firstName), e2d(oid.email), e2d(oid.country))
        )
      // case fid: IdentityTwitter => (User, SQL for Twitter identity table)
      // case fid: IdentityFacebook => (User, ...)
      case sid: IdentitySimple => assErr("[debiki_error_98239k2a2]")
      case IdentityUnknown => assErr("[debiki_error_92k2rI06]")
    }

    db.transaction { implicit connection =>
      var identitySno: Option[Long] = None
      var user: Option[User] = None

      // Load the User (if found) and any matching Identity.
      db.query(sqlSelectList + sqlFromWhere, bindVals, rs => {
        if (rs.next) {
          identitySno = Option(rs.getLong("IDENTITY_SNO"))
          user = Some(User(
              id = rs.getLong("USER_SNO").toString,
              displayName = n2e(rs.getString("DISPLAY_NAME")),
              email = n2e(rs.getString("EMAIL")),
              country = n2e(rs.getString("COUNTRY")),
              website = n2e(rs.getString("WEBSITE")),
              isSuperAdmin = rs.getString("SUPERADMIN") == "T"))
        }
        Empty // stupid box
      })

      // Create a user if no one found, or update an existing user if
      // the login identity attributes have been changed (e.g. new OpenID
      // user email or country attributes).
      user match {
        case None =>
          // Create new user.
          val userSno = db.nextSeqNo("DW1_USERS_SNO")
          val u = templateUser.copy(id = userSno.toString)
          user = Some(u)
          db.update("""
              insert into DW1_USERS(
                  TENANT, SNO, DISPLAY_NAME, EMAIL, COUNTRY)
              values (
                  (select SNO from DW1_TENANTS where NAME = ?),
                  ?, ?, ?, ?)""",
              List(tenantId, userSno.asInstanceOf[AnyRef],
                  u.displayName, u.email, u.country))
        case Some(u) =>
          // Compare user attributes, perhaps update user.
          val u2 = templateUser.copy(id = u.id)
          if (u != u2) {
            // User attributes have changed since the user was created.
            // So update DW1_USERS.
            // (COULD add fields to _USERS that the user him/herself fills
            // in, that overrides any identity stuff, e.g. OpenID attributes.
            // But what to do in the future, when supporting many OpenID
            // accounts per user? Should attributes from the latest OpenID
            // login be propagated to _USERS? Or should one claimed_id
            // be the "main" id?)
            user = Some(u2)
            db.update("""
                update DW1_USERS
                set DISPLAY_NAME = ?, EMAIL = ?, COUNTRY = ?
                where tenant = (select SNO from DW1_TENANTS where NAME = ?)
                  and SNO = ?""",  // ok cmp w String?
                List(u2.displayName, u2.email, u2.country, tenantId, u.id))
          }
        case _ =>
          // Do nothing. A user was found with up to date name, email etc.
      }

      if (identitySno isEmpty) {
        // This is a new user *or* it's an old user but with modified
        // Identity attributes (and there is no DW1_IDS_<identity-type>
        // row matching the new set of attributes). (Example: an OpenID user
        // who has changed her name or email.)
        // Create a new DW1_IDS_<identity-type> row.
        identitySno = Some(db.nextSeqNo("DW1_IDS_SNO"))
        loginStuff.identity match {
          case oid: IdentityOpenId =>
            // BUG: What if 2 threads create 2 different _IDS_OPENID rows
            // and 2 different users? But with the same tenant+claimed_id
            // Then there'll be no UK error if name/email/whatever differs,
            // and 2 _OPENID users with the same tenant+claimed_id, but
            // that should be forbidden!
            // However, OpenID user are supposedly real people
            // and they shouldn't be able to login with one single OpenID
            // account with *different* attributes at the same time.
            // Anyway, there ought to be a table related to _IDS_SIMPLE
            // with a UK on tenant+name+email. And one related to _IDS_OPENID
            // with a UK on tenant+claimed_id.
            // Or: Let the user ID be a hash of
            //   tenant+name+email / tenant+claimed_id
            //  But if the email is in the hash, there ought to be a secret
            // salt too (or one could brute-force-crack/dictionary-attack/guess
            // which email does he/she have). And e.g. SHA1 would have to
            // be used forever.
            // Perhaps better split tables:
            //   DW1_IDS_SIMPLE(tenant+name+email --> user) +
            //   DW1_IDS_SIMPLE_ATRS(location, website)
            //   DW1_IDS_OPENID(tenant+claimed_id --> user) +
            //   DW1_IDS_OPENID_ATRS(name, email..., and MAIN_SNO --> _OPENID)
            // Actually, this'd be very useful:
            //   _OPENID(tenant, claimed_id, latest_name, latest_email, ...)
            // -- then the latest_name etc could be used, unless _USERS.name
            // is non-null, then it takes precedence.
            // So: TODO: Split _OPENID and _SIMPLE into 2 tables. (normalize)
            //
            db.update("""
                insert into DW1_IDS_OPENID(
                    SNO, TENANT, USR, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                    OID_REALM, OID_ENDPOINT, OID_VERSION,
                    FIRST_NAME, EMAIL, COUNTRY)
                values (
                    ?, (select SNO from DW1_TENANTS where NAME = ?), ?,
                    ?, ?, ?, ?, ?, ?, ?, ?)""",
                List(identitySno.get.asInstanceOf[AnyRef],
                    tenantId, user.get.id, oid.oidClaimedId,
                    e2d(oid.oidOpLocalId), e2d(oid.oidRealm),
                    e2d(oid.oidEndpoint), e2d(oid.oidVersion),
                    e2d(oid.firstName), e2d(oid.email), e2d(oid.country)))
          // case IdentityTwitter => insert into Twitter identities table
          // case IdentityFacebook => ...
          case _: IdentitySimple => assErr("[debiki_error_83209qk12kt0]")
          case IdentityUnknown => assErr("[debiki_error_32ks30016")
        }
      }

      val (loginType, identityWithId) = loginStuff.identity match {
        case s: IdentitySimple => ("Simple", s.copy(
                                        id = identitySno.get.toString,
                                        userId = user.get.id))
        case o: IdentityOpenId => ("OpenID", o.copy(
                                        id = identitySno.get.toString,
                                        userId = user.get.id))
        case IdentityUnknown => assErr("[debiki_error_03k2r21K5]")
      }

      val loginWithId = _saveLogin(
            loginStuff.login.copy(identityId = identityWithId.id),
            identityType = "OpenID")

      Full(LoggedInStuff(loginWithId, identityWithId, user.get))
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
        where t.NAME = ?
          and p.TENANT = t.SNO
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
    // COULD split IdentityOpenId into IdentityOpenId and IdentityOpenIdDetails
    // -- the latter would contain e.g. endpoint and op-local-id and such.
    // And not load all attrs here.
    db.queryAtnms("""
        select o.SNO, o.USR, o.OID_ENDPOINT, o.OID_VERSION, o.OID_REALM,
              o.OID_CLAIMED_ID, o.OID_OP_LOCAL_ID, o.FIRST_NAME,
              o.EMAIL, o.COUNTRY
        from DW1_PAGE_ACTIONS a, DW1_LOGINS l, DW1_IDS_OPENID o
        where a.PAGE = ?
          and a.LOGIN = l.SNO
          and l.ID_TYPE = 'OpenID'
          and l.ID_SNO = o.SNO
          and o.TENANT = (select SNO from DW1_TENANTS where NAME = ?)""",
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

    // Load users. First find all relevant identities, by joining
    // DW1_PAGE_ACTIONS and _LOGINS. Then all user ids, by joining
    // the result with _IDS_SIMPLE and _IDS_OPENID. Then load the users.
    db.queryAtnms("""
        with logins as (
            select l.ID_SNO, l.ID_TYPE
            from DW1_PAGE_ACTIONS a, DW1_LOGINS l
            where a.PAGE = ? and a.LOGIN = l.SNO),
          identities as (
            select oi.USR
            from DW1_IDS_OPENID oi, logins
            where oi.SNO = logins.ID_SNO and logins.ID_TYPE = 'OpenID'
            -- union
            -- Twitter and Facebook identity tables, etc.
            )
        select u.SNO, u.DISPLAY_NAME, u.EMAIL, u.COUNTRY,
            u.WEBSITE, u.SUPERADMIN
        from DW1_USERS u, identities
        where u.SNO = identities.USR
          and u.TENANT = (select SNO from DW1_TENANTS where NAME = ?)
        """, List(pageSno.toString, tenantId), rs => {
      while (rs.next) {
        users ::= User(
            id = rs.getLong("SNO").toString,
            displayName = n2e(rs.getString("DISPLAY_NAME")),
            email = n2e(rs.getString("EMAIL")),
            country = n2e(rs.getString("COUNTRY")),
            website = n2e(rs.getString("WEBSITE")),
            isSuperAdmin = rs.getString("SUPERADMIN") == "T")
      }
      Empty // silly box
    })

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

  def checkPagePath(pathToCheck: PagePath): Box[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def checkAccess(pagePath: PagePath, loginId: Option[String], doo: Do
                     ): Option[IntrsAllowed] = {
    /*
    The algorithm: (a sketch)
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

    val intrsOk: IntrsAllowed = {
      val p = pagePath.path
      if (p == "/test/") VisibleTalk
      else if (p == "/allt/") VisibleTalk
      else if (p == "/forum/") VisibleTalk
      else if (doo == Do.Create)
        return Empty  // don't allow new pages in the below paths
      else if (p == "/blogg/") VisibleTalk
      else if (p == "/arkiv/") VisibleTalk
      else HiddenTalk
    }
    Some(intrsOk)
  }

  // Looks up the correct PagePath for a possibly incorrect PagePath.
  private def _findCorrectPagePath(pagePathIn: PagePath): Box[PagePath] = {
    var query = """
        select FOLDER, PAGE_GUID, PAGE_NAME, GUID_IN_PATH
        from DW1_PATHS
        where TENANT = (select SNO from DW1_TENANTS where NAME = ?)
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
        values (
            DW1_PAGES_SNO.nextval,
            (select SNO from DW1_TENANTS where NAME = ?),
            ?)
        """, List(where.tenantId, debate.guid))

    db.update("""
        insert into DW1_PATHS (TENANT, FOLDER, PAGE_GUID,
                                   PAGE_NAME, GUID_IN_PATH)
        values ((select SNO from DW1_TENANTS where NAME = ?), ?, ?, ?, ?)
        """,
        List(where.tenantId, where.parent, debate.guid, e2s(where.name),
          "T"  // means guid will prefix page name: /folder/-guid-pagename
        ))
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
            where TENANT = (select SNO from DW1_TENANTS where NAME = ?)
              and GUID = ?
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
