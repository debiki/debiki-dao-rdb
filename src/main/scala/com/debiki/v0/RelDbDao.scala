/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.Dao._
import com.debiki.v0.EmailNotfPrefs.EmailNotfPrefs
import _root_.net.liftweb.common.{Loggable, Box, Empty, Full, Failure}
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import scala.collection.{mutable => mut}
import RelDb.pimpOptionWithNullVarchar
import collection.mutable.StringBuilder


class RelDbDaoSpi(val db: RelDb) extends DaoSpi with Loggable {
  // COULD serialize access, per page?

  import RelDb._

  def close() { db.close() }

  def checkRepoVersion() = Full("0.0.2")

  def secretSalt(): String = "9KsAyFqw_"

  def createPage(where: PagePath, debatePerhapsGuid: Debate): Box[Debate] = {
    var debate = if (debatePerhapsGuid.guid != "?") {
      unimplemented
      // Could use debatePerhapsGuid, instead of generatinig a new guid,
      // but i have to test that this works. But should where.guid
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
          List(loginSno.asInstanceOf[AnyRef], tenantId,
              e2n(login.prevLoginId),  // UNTESTED unless empty
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
      case idtySmpl: IdentitySimple =>
        val simpleLogin = db.transaction { implicit connection =>
          // Find or create _SIMPLE row.
          var idtyId = ""
          var emailNotfsStr = ""
          for (i <- 1 to 2 if idtyId.isEmpty) {
            db.query("""
                select i.SNO, e.EMAIL_NOTFS from DW1_IDS_SIMPLE i
                  left join DW1_IDS_SIMPLE_EMAIL e
                  on i.EMAIL = e.EMAIL and e.VERSION = 'C'
                where i.NAME = ? and i.EMAIL = ? and i.LOCATION = ? and
                      i.WEBSITE = ?
                """,
                List(e2d(idtySmpl.name), e2d(idtySmpl.email),
                   e2d(idtySmpl.location), e2d(idtySmpl.website)),
                rs => {
              if (rs.next) {
                idtyId = rs.getString("SNO")
                emailNotfsStr = rs.getString("EMAIL_NOTFS")
              }
              Empty // dummy
            })
            if (idtyId isEmpty) {
              // Create simple user info.
              // There is a unique constraint on NAME, EMAIL, LOCATION,
              // WEBSITE, so this insert might fail (if another thread does
              // the insert, just before). Should it fail, the above `select'
              // is run again and finds the row inserted by the other thread.
              // Could avoid logging any error though!
              db.update("""
                  insert into DW1_IDS_SIMPLE(
                      SNO, NAME, EMAIL, LOCATION, WEBSITE)
                  values (nextval('DW1_IDS_SNO'), ?, ?, ?, ?)""",
                  List(idtySmpl.name, e2d(idtySmpl.email),
                     e2d(idtySmpl.location), e2d(idtySmpl.website)))
                  // COULD fix: returning SNO into ?""", saves 1 roundtrip.
              // Loop one more lap to read SNO.
            }
          }
          assErrIf3(idtyId.isEmpty, "DwE3kRhk20")
          val notfPrefs: EmailNotfPrefs = _toEmailNotfs(emailNotfsStr)
          val user = _dummyUserFor(identity = idtySmpl,
             emailNotfPrefs = notfPrefs, id = _dummyUserIdFor(idtyId))
          val identityWithId = idtySmpl.copy(id = idtyId, userId = user.id)
          val loginWithId = _saveLogin(loginReq.login, identityWithId)
          Full(LoginGrant(loginWithId, identityWithId, user))
        }.open_!

        return simpleLogin

      case _ => ()
    }

    // Load the User, if any, and the OpenID/Twitter/Facebook identity id:
    // Construct a query that joins the relevant DW1_IDS_<id-type> table
    // with DW1_USERS.
    // COULD move this user loading code to _loadIdtysAndUsers?
    var sqlSelectList = """
        select
            u.SNO USER_SNO,
            u.DISPLAY_NAME,
            u.EMAIL,
            u.EMAIL_NOTFS,
            u.COUNTRY,
            u.WEBSITE,
            u.SUPERADMIN,
            i.SNO IDENTITY_SNO,
            i.USR,
            i.USR_ORIG,
            """

    // SQL for selecting User and Identity. Depends on the Identity type.
    val (sqlFromWhere, bindVals) = loginReq.identity match {
      case oid: IdentityOpenId =>
        ("""i.OID_OP_LOCAL_ID,
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
      // case fid: IdentityTwitter => (SQL for Twitter identity table)
      // case fid: IdentityFacebook => (...)
      case _: IdentitySimple => assErr("DwE98239k2a2")
      case IdentityUnknown => assErr("DwE92k2rI06")
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
              emailNotfPrefs = _toEmailNotfs(
                                  rs.getString("EMAIL_NOTFS")),
              country = n2e(rs.getString("COUNTRY")),
              website = n2e(rs.getString("WEBSITE")),
              isSuperAdmin = rs.getString("SUPERADMIN") == "T")
          val identityInDb = loginReq.identity match {
            case iod: IdentityOpenId =>
              IdentityOpenId(
                id = rs.getLong("IDENTITY_SNO").toString,
                userId = userInDb.id,
                // COULD use d2e here, or n2e if I store Null instead of '-'.
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
            case sid: IdentitySimple => assErr("DwE8451kx35")
            case IdentityUnknown => assErr("DwE091563wkr2")
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
          // Leave the name/email/etc fields blank --  the name/email from
          // the relevant identity is used instead. However, if the user
          // manually fills in (not implemented) her user data,
          // then those values take precedence (over the one from the
          // identity provider).
          val userSno = db.nextSeqNo("DW1_USERS_SNO")
          val u =  User(id = userSno.toString, displayName = "", email = "",
                        emailNotfPrefs = EmailNotfPrefs.DontReceive,
                        country = "", website = "", isSuperAdmin = false)
          db.update("""
              insert into DW1_USERS(
                  TENANT, SNO, DISPLAY_NAME, EMAIL, COUNTRY)
              values (?, ?, ?, ?, ?)""",
              List(tenantId, userSno.asInstanceOf[AnyRef],
                  e2n(u.displayName), e2n(u.email), e2n(u.country)))
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
            // COULD aovid overwriting email with nothing?
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
        case (_, _: IdentitySimple) => assErr("DwE83209qk12")
        case (_, IdentityUnknown) => assErr("DwE32ks30016")
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
        case Full(x) => assErr("DwE0kSRIE3", "Updated "+ x +" rows")
        case badBox => unimplemented // remove boxes
      }
    }
  }

  def loadUser(withLoginId: String, tenantId: String
                  ): Option[(Identity, User)] = {
    def loginInfo = "login id "+ safed(withLoginId) +
          ", tenant "+ safed(tenantId)

    _loadIdtysAndUsers(withLoginId = withLoginId, tenantId = tenantId) match {
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
        // -- The server could do that if a failover happens to a standby
        // database, and a few transactions were lost when the master died?!
        // COULD throw an exception and let the HTTP module delete the
        // dwCoSid cookie.
        error("Found no identity for "+ loginInfo +". Has the server" +
            " connected to a standby database? Please delete your " +
            " session cookie, \"dwCoSid\". [error DwE0921kxa13]")
      case (is, us) =>
        // There should be exactly one identity per login, and at most
        // one user per identity.
        assErr("DwE42RxkW1", "Found "+ is.length +" identities and "+
              us.length +" users for "+ loginInfo)
    }
  }


  private def _loadIdtysAndUsers(onPageWithSno: String = null,
                         withLoginId: String = null,
                         tenantId: String
                            ): Pair[List[Identity], List[User]] = {
    // Load users. First find all relevant identities, by joining
    // DW1_PAGE_ACTIONS and _LOGINS. Then all user ids, by joining
    // the result with _IDS_SIMPLE and _IDS_OPENID. Then load the users.

    val (selectLoginIds, args) = (onPageWithSno, withLoginId) match {
      // (Need to specify tenant id here, and when selecting from DW1_USERS,
      // because there's no foreign key from DW1_LOGINS to DW1_IDS_<type>.)
      case (null, loginId) => ("""
          select ID_SNO, ID_TYPE
              from DW1_LOGINS
              where SNO = ? and TENANT = ?
          """, List(loginId, // why not needed: .toLong.asInstanceOf[AnyRef]?
                     tenantId))
      case (pageSno, null) => ("""
          select distinct l.ID_SNO, l.ID_TYPE
              from DW1_PAGE_ACTIONS a, DW1_LOGINS l
              where a.PAGE = ? and a.LOGIN = l.SNO and l.TENANT = ?
          """, List(pageSno.asInstanceOf[AnyRef], tenantId))
      case (a, b) => illArgErr(
          "DwE0kEF3", "onPageWithSno: "+ safed(a) +", withLoginId: "+ safed(b))
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
            select ID_TYPE, s.SNO I_ID, '' I_USR,
                   s.NAME I_NAME, s.EMAIL I_EMAIL,
                   e.EMAIL_NOTFS I_EMAIL_NOTFS,
                   s.LOCATION I_WHERE, s.WEBSITE I_WEBSITE
            from logins, DW1_IDS_SIMPLE s
              left join DW1_IDS_SIMPLE_EMAIL e
              on s.EMAIL = e.EMAIL and e.VERSION = 'C'
            where s.SNO = logins.ID_SNO and logins.ID_TYPE = 'Simple'
            union
            -- OpenID
            select ID_TYPE, oi.SNO I_ID, oi.USR,
                   oi.FIRST_NAME I_NAME, oi.EMAIL I_EMAIL,
                   null as I_EMAIL_NOTFS,
                   oi.COUNTRY I_WHERE, cast('' as varchar(100)) I_WEBSITE
            from DW1_IDS_OPENID oi, logins
            where oi.SNO = logins.ID_SNO and logins.ID_TYPE = 'OpenID'
            -- union
            -- Twitter tables
            -- Facebook tables
            )
        select i.ID_TYPE, i.I_ID,
            i.I_NAME, i.I_EMAIL,
            case
              -- Can only be non-null for IdentitySimple.
              when i.I_EMAIL_NOTFS is not null then i.I_EMAIL_NOTFS
              else u.EMAIL_NOTFS end  -- might be null
              EMAIL_NOTFS,
            i.I_WHERE, i.I_WEBSITE,
            u.SNO U_ID,
            u.DISPLAY_NAME U_DISP_NAME,
            u.EMAIL U_EMAIL,
            u.COUNTRY U_COUNTRY,
            u.WEBSITE U_WEBSITE,
            u.SUPERADMIN U_SUPERADMIN
        from identities i left join DW1_USERS u on
              u.SNO = i.I_USR and
              u.TENANT = ?
        """, args ::: List(tenantId), rs => {
      var usersById = mut.HashMap[String, User]()
      var identities = List[Identity]()
      while (rs.next) {
        val idId = rs.getString("I_ID")
        var userId = rs.getLong("U_ID").toString  // 0 if null
        var user: Option[User] = None
        assErrIf3(idId isEmpty, "DwE392Qvc89")
        val emailPrefs = _toEmailNotfs(rs.getString("EMAIL_NOTFS"))

        identities ::= (rs.getString("ID_TYPE") match {
          case "Simple" =>
            userId = _dummyUserIdFor(idId)
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
                oidEndpoint = "?",
                oidVersion = "?",
                oidRealm = "?",
                oidClaimedId = "?",
                oidOpLocalId = "?",
                firstName = n2e(rs.getString("I_NAME")),
                email = n2e(rs.getString("I_EMAIL")),
                country = n2e(rs.getString("I_WHERE")))
        })

        if (user isEmpty) user = Some(User(
            id = userId,
            displayName = n2e(rs.getString("U_DISP_NAME")),
            email = n2e(rs.getString("U_EMAIL")),
            emailNotfPrefs = emailPrefs,
            country = n2e(rs.getString("U_COUNTRY")),
            website = n2e(rs.getString("U_WEBSITE")),
            isSuperAdmin = rs.getString("U_SUPERADMIN") == "T"))

        if (!usersById.contains(userId)) usersById(userId) = user.get
      }
      Full((identities,
          usersById.values.toList))  // silly to throw away hash map
    }).open_! // silly box
  }


  private def _loadUsers(userIdsByTenant: Map[String, List[String]])
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
      val q = """
         select
            e.TENANT, '-'||s.SNO user_id, s.NAME DISPLAY_NAME,
            s.EMAIL, e.EMAIL_NOTFS, s.LOCATION COUNTRY,
            s.WEBSITE, 'F' SUPERADMIN
         from
           DW1_IDS_SIMPLE s left join DW1_IDS_SIMPLE_EMAIL e
           on s.EMAIL = e.EMAIL and e.TENANT = ?
         where
           s.SNO in (""" + inList +")"
      // An unauthenticated user id starts with '-', drop it.
      val vals = tenantId :: idsUnau.map(_.drop(1))
      (q, vals)
    }

    def mkQueryAu(tenantId: String, idsAu: List[String])
          : (String, List[String]) = {
      incIdCount(idsAu)
      val inList = idsAu.map(_ => "?").mkString(",")
      val q = """
         select TENANT, SNO user_id, DISPLAY_NAME,
            EMAIL, EMAIL_NOTFS, COUNTRY,
            WEBSITE, SUPERADMIN
         from DW1_USERS
         where TENANT = ?
         and SNO in (""" + inList +")"
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
      // Split user ids into distinct authenticated and unauthenticated ids.
      // Unauthenticated id starts with "-".
      val (idsUnau, idsAu) = userIds.distinct.partition(_ startsWith "-")

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
        val tenantId = rs.getString("TENANT")
        // Sometimes convert both "-" and null to "", because unauthenticated
        // users use "-" as placeholder for "nothing specified" -- so those
        // values are indexed (since sql null isn't).
        // Authenticated users, however, currently use sql null for nothing.
        val user = User(
           id = rs.getString("user_id"),
           displayName = dn2e(rs.getString("DISPLAY_NAME")),
           email = dn2e(rs.getString("EMAIL")),
           emailNotfPrefs = _toEmailNotfs(rs.getString("EMAIL_NOTFS")),
           country = dn2e(rs.getString("COUNTRY")),
           website = dn2e(rs.getString("WEBSITE")),
           isSuperAdmin = rs.getString("SUPERADMIN") == "T")

        usersByTenantAndId = usersByTenantAndId + ((tenantId, user.id) -> user)
      }
      Empty // dummy
    })

    usersByTenantAndId
  }


  private def _loadMemships(r: RequestInfo): List[String] = {
    Nil
  }

  override def savePageActions[T <: Action](tenantId: String, pageGuid: String,
                                  xs: List[T]): Box[List[T]] = {
    db.transaction { implicit connection =>
      _insert(tenantId, pageGuid, xs)
    }
  }

  def loadPage(tenantId: String, pageGuid: String): Box[Debate] = {
    /*
    db.transaction { implicit connection =>
      // BUG: There might be a NPE / None.get because of phantom reads.
      // Prevent phantom reads from DW1_ACTIONS. (E.g. rating tags are read
      // from DW1_RATINGS before _ACTIONS is considered, and another session
      // might insert a row into _ACTIONS just after _RATINGS was queried.)
      connection.setTransactionIsolation(
        Connection.TRANSACTION_SERIALIZABLE)
      */

    var pageSno = ""
    var logins = List[Login]()

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
        pageSno = rs.getString("PAGE_SNO")
        val loginId = rs.getLong("LOGIN_SNO").toString
        val prevLogin = Option(rs.getLong("PREV_LOGIN")).map(_.toString)
        val ip = rs.getString("LOGIN_IP")
        val date = ts2d(rs.getTimestamp("LOGIN_TIME"))
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

    // Load identities and users.
    val (identities, users) =
        _loadIdtysAndUsers(onPageWithSno = pageSno, tenantId = tenantId)

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

    // Load page actions.
    // Order by TIME desc, because when the action list is constructed
    // the order is reversed again.
    db.queryAtnms("""
        select PAID, LOGIN, TIME, TYPE, RELPA,
              TEXT, MARKUP, WHEERE, NEW_IP
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

        // (This whole match-case will go away when I unify all types
        // into Post?)  ...
        val action = typee match {
                     // ... then this ugly if ..||..||.. won't be an issue.
          case typeStr if typeStr == "Post" ||
                typeStr == "Publ" || typeStr == "Meta" =>
            // How repr empty root post parent? ' ' or '-' or '_' or '0'?
            new Post(id = id, parent = relpa, ctime = time,
              loginId = loginSno, newIp = newIp, text = n2e(text_?),
              markup = n2e(markup_?), tyype = _toPostType(typeStr),
              where = Option(where_?))
          case "Rating" =>
            val tags = ratingTags(id)
            new Rating(id = id, postId = relpa, ctime = time,
              loginId = loginSno, newIp = newIp, tags = tags)
          case "Edit" =>
            new Edit(id = id, postId = relpa, ctime = time,
              loginId = loginSno, newIp = newIp, text = n2e(text_?),
              newMarkup = Option(markup_?))
          case "EditApp" =>
            new EditApp(id = id, editId = relpa, ctime = time,
              loginId = loginSno, newIp = newIp,
              result = n2e(text_?))
          case flag if flag startsWith "Flag" =>
            val reasonStr = flag drop 4 // drop "Flag"
            val reason = FlagReason withName reasonStr
            Flag(id = id, postId = relpa, loginId = loginSno, newIp = newIp,
                ctime = time, reason = reason, details = n2e(text_?))
          case delete if delete startsWith "Del" =>
            val wholeTree = delete match {
              case "DelTree" => true
              case "DelPost" => false
              case x => assErr("DwE0912k22")
            }
            Delete(id = id, postId = relpa, loginId = loginSno, newIp = newIp,
                ctime = time, wholeTree = wholeTree, reason = n2e(text_?))
          case x => return Failure(
              "Bad DW1_ACTIONS.TYPE: "+ safed(typee) +" [error DwEY8k3B]")
        }
        actions ::= action  // this reverses above `order by TIME desc'
      }

      Full(Debate.fromActions(guid = pageGuid,
          logins, identities, users, actions))
    })
  }

  def loadTemplate(templPath: PagePath): Option[TemplateSrcHtml] = {
    // Minor bug: if template /some-page.tmpl does not exist, but there's
    // an odd page /some-page.tmpl/, then that *page* is found and
    // returned although it's probably not a template.  ? Solution:
    // TODO disallow '.' in directory names? but allow e.g. style.css,
    // scripts.js, template.tpl.

    val templ = _findCorrectPagePath(templPath) match {
      // If the template does not exist:
      case Empty => None
      // If there's a database error when looking up the path:
      case f: Failure =>
        runErr("DwE309sU32", "Error loading template guid:\n"+ f)
      case Full(path) =>
        loadPage(path.tenantId, path.pageId.get) match {
          case Full(page) => page.body map (TemplateSrcHtml(_))
          // If someone deleted the template moments ago, after its
          // guid was found:
          case Empty => None
          // If there's some database error or problem with the template?:
          case f: Failure =>
            val err = "Error loading template [error DwE983keCK31]"
            logger.error(err +":"+ f.toString) //COULD fix consistent err reprt
            runErr("DwE983keCK31", err)
      }
    }
    templ
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

  def loadTenants(tenantIds: Seq[String]): Seq[Tenant] = {
    // For now, load only tenant names, and only 1 tenant.
    require(tenantIds.length == 1)

    var tenants = List[Tenant]()
    db.queryAtnms("""
        select ID, NAME from DW1_TENANTS where ID = ?
        """,
        List(tenantIds.head),
        rs => {
      while (rs.next) {
        tenants ::= Tenant(
          id = rs.getString("ID"), name = rs.getString("NAME"), hosts = Nil)
      }
      Empty // my stupid API, rewrite some day?
    })
    tenants
  }

  def addTenantHost(tenantId: String, host: TenantHost) = {
    db.transaction { implicit connection =>
      val cncl = host.role match {
        case TenantHost.RoleCanonical => "C"
        case TenantHost.RoleRedirect => "R"
        case TenantHost.RoleLink => "L"
        case TenantHost.RoleDuplicate => "D"
      }
      val https = host.https match {
        case TenantHost.HttpsRequired => "R"
        case TenantHost.HttpsAllowed => "A"
        case TenantHost.HttpsNone => "N"
      }
      db.update("""
          insert into DW1_TENANT_HOSTS (TENANT, HOST, CANONICAL, HTTPS)
          values (?, ?, ?, ?)
          """,
          List(tenantId, host.address, cncl, https))
    }
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
        select t.TENANT TID,
            t.CANONICAL THIS_CANONICAL, t.HTTPS THIS_HTTPS,
            c.HOST CANONICAL_HOST, c.HTTPS CANONICAL_HTTPS
        from DW1_TENANT_HOSTS t -- this host, the one connected to
            left join DW1_TENANT_HOSTS c  -- the cannonical host
            on c.TENANT = t.TENANT and c.CANONICAL = 'C'
        where t.HOST = ?
        """, List(host), rs => {
      if (!rs.next) return FoundNothing
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

      Full((thisRole, scheme, thisHttps) match {
        case (RoleCanonical, "http" , HttpsRequired) => redirect
        case (RoleCanonical, "http" , _            ) => useThisHostAndScheme
        case (RoleCanonical, "https", HttpsRequired) => useThisHostAndScheme
        case (RoleCanonical, "https", HttpsAllowed ) => useLinkRelCanonical
        case (RoleCanonical, "https", HttpsNo      ) => redirect
        case (RoleRedirect , _      , _            ) => redirect
        case (RoleLink     , _      , _            ) => useLinkRelCanonical
        case (RoleDuplicate, _      , _            ) => assErr("DwE09KL04")
      })
    }).open_!
  }

  def checkPagePath(pathToCheck: PagePath): Box[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def listPagePaths(
    withFolderPrefix: String,
    tenantId: String,
    includeStatuses: List[PageStatus],
    sortBy: PageSortOrder,
    limit: Int,
    offset: Int
  ): Seq[(PagePath, PageDetails)] = {

    require(limit == Int.MaxValue) // for now
    require(offset == 0)  // for now

    val statusesToInclStr =
      includeStatuses.map(_toFlag).mkString("'", "','", "'")
    if (statusesToInclStr isEmpty)
      return Nil

    val orderByStr = sortBy match {
      case PageSortOrder.ByPath =>
        " order by PARENT_FOLDER, SHOW_ID, PAGE_SLUG"
      case PageSortOrder.ByPublTime =>
        " order by CACHED_PUBL_TIME desc"
    }

    var items = List[(PagePath, PageDetails)]()
    db.queryAtnms("""
        select PARENT_FOLDER,
            PAGE_ID,
            SHOW_ID,
            PAGE_SLUG,
            PAGE_STATUS,
            CACHED_TITLE,
            CACHED_PUBL_TIME,
            CACHED_SGFNT_MTIME
        from DW1_PAGE_PATHS
        where TENANT = ? and PARENT_FOLDER like ?
        and PAGE_STATUS in ("""+ statusesToInclStr +""")
        """+ orderByStr,
        List(tenantId, withFolderPrefix +"%"),
        rs => {
      while (rs.next) {
        val pagePath = PagePath(
          tenantId = tenantId,
          folder = rs.getString("PARENT_FOLDER"),
          pageId = Some(rs.getString("PAGE_ID")),
          showId = rs.getString("SHOW_ID") == "T",
          pageSlug = d2e(rs.getString("PAGE_SLUG"))
        )
        val pageDetails = PageDetails(
          status = _toPageStatus(rs.getString("PAGE_STATUS")),
          cachedTitle =
              Option(rs.getString(("CACHED_TITLE"))),
          cachedPublTime =
              Option(rs.getTimestamp("CACHED_PUBL_TIME")).map(ts2d _),
          cachedSgfntMtime =
              Option(rs.getTimestamp("CACHED_SGFNT_MTIME")).map(ts2d _)
        )
        items ::= pagePath -> pageDetails
      }
      Empty // dummy
    })
    items
  }

  def loadPermsOnPage(reqInfo: RequestInfo): PermsOnPage = {
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

    // Allow superadmins to do anything, e.g. create pages anywhere.
    // (Currently users can edit their own pages only.)
    if (reqInfo.user.map(_.isSuperAdmin) == Some(true))
      return PermsOnPage.All

    // Files whose name starts with '.' are hidden, only admins have access.
    if (reqInfo.pagePath.isHiddenPage)
      return PermsOnPage.None

    // People may view and use Javascript and CSS, but of course not edit it.
    if (reqInfo.pagePath.isCodePage)
      return PermsOnPage.None.copy(accessPage = true)

    // Non-admins can only create pages whose names are prefixed
    // with their guid, like so: /folder/-guid-pagename.
    // (Currently there are no admin users, only superadmins)
    // This is done by invoking /folder/?createpage.
    // Admins can do this: /folder/page-name?createpage
    (reqInfo.doo, reqInfo.pagePath.isFolderPath) match {
      case (Do.CreatePage, true) => () // a page will be created in this folder
      case (Do.CreatePage, false) =>
        // A page name was specified, not a folder. Deny.
        return PermsOnPage.None
      case _ => ()
    }

    // For now, hardcode rules here:
    val mayCreatePage = {
      val p = reqInfo.pagePath.path
      if (p == "/test/") true
      else if (p == "/allt/") true
      else if (p == "/forum/") true
      else if (p == "/wiki/") true
      else false
    }

    val isWiki = reqInfo.pagePath.folder == "/wiki/"

    PermsOnPage.Wiki.copy(
      createPage = mayCreatePage,
      editPage = isWiki,
      // Authenticated users can edit others' comments.
      // (In the future, the reputation system (not implemented) will make
      // them lose this ability should they misuse it.)
      editAnyReply =
            isWiki || reqInfo.user.map(_.isAuthenticated) == Some(true)
    )
  }


  def saveNotfs(tenantId: String, notfs: Seq[NotfOfPageAction]) {
    db.transaction { implicit connection =>
      val valss: List[List[AnyRef]] = for (notf <- notfs.toList) yield List(
        tenantId, notf.ctime, notf.pageId, notf.pageTitle,
        notf.recipientIdtySmplId.orNullVarchar,
        notf.recipientRoleId.orNullVarchar,
        notf.eventType.toString, notf.eventActionId,
        notf.targetActionId.orNullVarchar,
        notf.recipientActionId,
        notf.recipientUserDispName, notf.eventUserDispName,
        notf.targetUserDispName.orNullVarchar)

      db.batchUpdate("""
        insert into DW1_NOTFS_PAGE_ACTIONS(
            TENANT, CTIME, PAGE_ID, PAGE_TITLE,
            RCPT_ID_SIMPLE, RCPT_ROLE_ID,
            EVENT_TYPE, EVENT_PGA, TARGET_PGA, RCPT_PGA,
            RCPT_USER_DISP_NAME, EVENT_USER_DISP_NAME, TARGET_USER_DISP_NAME)
          values (
            ?, ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?, ?,
            ?)
        """, valss)
      Empty  // my stupid API, should rewrite
    }
  }


  def loadNotfsForRole(tenantId: String, userId: String)
        : Seq[NotfOfPageAction] = {
    val numToLoad = 50 // for now
    val notfsToMail = _loadNotfsImpl(numToLoad, Some(tenantId),
       delayMins = None, userId = Some(userId))
    // All loaded notifications are to userId only.
    notfsToMail.notfsByTenant(tenantId)
  }


  def loadNotfsToMailOut(delayInMinutes: Int, numToLoad: Int): NotfsToMail =
    _loadNotfsImpl(numToLoad, None, delayMins = Some(delayInMinutes),
       userId = None)


  def _loadNotfsImpl(numToLoad: Int, tenantId: Option[String],
        delayMins: Option[Int], userId: Option[String])
        : NotfsToMail = {

    assert(delayMins.isDefined != userId.isDefined)
    assert(delayMins.isDefined != tenantId.isDefined)
    assert(numToLoad > 0)
    // When loading email addrs, an SQL in list is used, but
    // Oracle limits the max in list size to 1000. As a stupid workaround,
    // don't load more than 1000 notifications at a time.
    assert(numToLoad < 1000)

    val baseQuery = """
       select
         TENANT, CTIME, PAGE_ID, PAGE_TITLE,
         RCPT_ID_SIMPLE, RCPT_ROLE_ID,
         EVENT_TYPE, EVENT_PGA, TARGET_PGA, RCPT_PGA,
         RCPT_USER_DISP_NAME, EVENT_USER_DISP_NAME, TARGET_USER_DISP_NAME,
         STATUS, EMAIL_SENT, EMAIL_LINK_CLICKED
       from DW1_NOTFS_PAGE_ACTIONS
       where """

    val (whereOrderBy, vals) = userId match {
      case Some(uid) =>
        var whereOrderBy =
           "TENANT = ? and "+ (
           if (uid startsWith "-") "RCPT_ID_SIMPLE = ?"
           else "RCPT_ROLE_ID = ?"
           ) +" order by CTIME desc"
        // IdentitySimple user ids start with '-'.
        val uidNoDash = uid.dropWhile(_ == '-')
        val vals = List(tenantId.get, uidNoDash)
        (whereOrderBy, vals)
      case None =>
        // (For all tenants.)
        val whereOrderBy = "CTIME <= ? order by CTIME asc"
        val nowInMillis = (new ju.Date).getTime
        val someMinsAgo = new ju.Date(nowInMillis - delayMins.get * 60 * 1000)
        val vals = someMinsAgo::Nil
        (whereOrderBy, vals)
    }

    val query = baseQuery + whereOrderBy +" limit "+ numToLoad
    var notfsByTenant =
       Map[String, List[NotfOfPageAction]]().withDefaultValue(Nil)

    db.queryAtnms(query, vals, rs => {
      while (rs.next) {
        val tenantId = rs.getString("TENANT")
        val eventTypeStr = rs.getString("EVENT_TYPE")
        val rcptIdSimple = rs.getString("RCPT_ID_SIMPLE")
        val rcptRoleId = rs.getString("RCPT_ROLE_ID")
        val rcptUserId =
          if (rcptRoleId ne null) rcptRoleId
          else "-"+ rcptIdSimple
        val notf = NotfOfPageAction(
          ctime = ts2d(rs.getTimestamp("CTIME")),
          recipientUserId = rcptUserId,
          pageTitle = rs.getString("PAGE_TITLE"),
          pageId = rs.getString("PAGE_ID"),
          eventType = NotfOfPageAction.Type.PersonalReply,  // for now
          eventActionId = rs.getString("EVENT_PGA"),
          targetActionId = Option(rs.getString("TARGET_PGA")),
          recipientActionId = rs.getString("RCPT_PGA"),
          recipientUserDispName = rs.getString("RCPT_USER_DISP_NAME"),
          eventUserDispName = rs.getString("EVENT_USER_DISP_NAME"),
          targetUserDispName = Option(rs.getString("TARGET_USER_DISP_NAME")))

        // Add notf to the list of all notifications for tenantId.
        val notfsForTenant: List[NotfOfPageAction] = notfsByTenant(tenantId)
        notfsByTenant = notfsByTenant + (tenantId -> (notf::notfsForTenant))
      }
      Empty // my bad API
    })

    val userIdsByTenant: Map[String, List[String]] =
       notfsByTenant.mapValues(_.map(_.recipientUserId))

    val usersByTenantAndId: Map[(String, String), User] =
      _loadUsers(userIdsByTenant)

    NotfsToMail(notfsByTenant, usersByTenantAndId)
  }


  def markNotfsAsMailed(
        notfEmailsByTenant: Map[String, Seq[(NotfOfPageAction, EmailSent)]]) {

  }


  def configRole(tenantId: String, loginId: String, ctime: ju.Date,
                 roleId: String, emailNotfPrefs: EmailNotfPrefs) {
    // Currently auditing not implemented for the roles/users table,
    // so loginId and ctime aren't used.
    db.transaction { implicit connection =>
      db.update("""
          update DW1_USERS
          set EMAIL_NOTFS = ?
          where TENANT = ? and SNO = ? and
              (EMAIL_NOTFS is null or EMAIL_NOTFS <> 'F')
          """,
          List(_toFlag(emailNotfPrefs), tenantId, roleId))
    }
  }

  def configIdtySimple(tenantId: String, loginId: String, ctime: ju.Date,
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
          List(tenantId, emailAddr))

      // Create a new row with the desired email notification setting.
      // Or, for now, fail and throw some SQLException if EMAIL_NOTFS is 'F'
      // for this `emailAddr' -- since there'll be a primary key violation,
      // see the update statement above.
      db.update("""
          insert into DW1_IDS_SIMPLE_EMAIL (
              TENANT, LOGIN, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, ?, 'C', ?, ?)
          """,
          List(tenantId, loginId, d2ts(ctime), emailAddr,
              _toFlag(emailNotfPrefs)))
    }
  }

  // Looks up the correct PagePath for a possibly incorrect PagePath.
  private def _findCorrectPagePath(pagePathIn: PagePath): Box[PagePath] = {
    var query = """
        select PARENT_FOLDER, PAGE_ID, SHOW_ID, PAGE_SLUG
        from DW1_PAGE_PATHS
        where TENANT = ?
        """
    var binds = List(pagePathIn.tenantId)
    var maxRowsFound = 1  // there's a unique key
    pagePathIn.pageId match {
      case Some(id) =>
        query += " and PAGE_ID = ?"
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
          query += """
              or (PARENT_FOLDER = ? and PAGE_SLUG = '-')
              )
            order by length(PARENT_FOLDER) asc
            """
          binds ::= pagePathIn.folder + pagePathIn.pageSlug +"/"
          maxRowsFound = 2
        }
        else if (pagePathIn.folder.count(_ == '/') >= 2) {
          // Perhaps the correct path is /folder/page not /folder/page/.
          // But prefer /folder/page/ if both pages are found.
          query += """
              or (PARENT_FOLDER = ? and PAGE_SLUG = ?)
              )
            order by length(PARENT_FOLDER) desc
            """
          val perhapsPath = pagePathIn.folder.dropRight(1)  // drop `/'
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
        correctPath = Full(pagePathIn.copy(  // keep pagePathIn.tenantId
            folder = rs.getString("PARENT_FOLDER"),
            pageId = Some(rs.getString("PAGE_ID")),
            showId = rs.getString("SHOW_ID") == "T",
            // If there is a root page ("serveraddr/") with no name,
            // it is stored as a single space; s2e removes such a space:
            pageSlug = d2e(rs.getString("PAGE_SLUG"))))
      }
      assert(maxRowsFound == 2 || !rs.next)
      correctPath
    })
  }

  private def _createPage[T](where: PagePath, debate: Debate)
                            (implicit conn: js.Connection): Box[Int] = {
    db.update("""
        insert into DW1_PAGES (SNO, TENANT, GUID)
        values (nextval('DW1_PAGES_SNO'), ?, ?)
        """, List(where.tenantId, debate.guid))

    // Concerning prefixing the page name with the page guid:
    // /folder/?createpage results in the guid prefixing the page name, like so:
    //    /folder/-guid-pagename
    // but /folder/pagename?createpage results in the guid being hidden,
    // and this'll be the path to the page:
    //    /folder/pagename
    val showPageId = if (where.isFolderPath) "T" else "F"

    // Create a draft, always a draft ('D') -- the user needs to
    // write something before it makes sense to publish the page.
    db.update("""
        insert into DW1_PAGE_PATHS (
          TENANT, PARENT_FOLDER, PAGE_ID, SHOW_ID, PAGE_SLUG, PAGE_STATUS)
        values (?, ?, ?, ?, ?, 'D')
        """,
        List(where.tenantId, where.folder, debate.guid, showPageId,
            e2d(where.pageSlug)))
  }

  private def _insert[T <: Action](
        tenantId: String, pageGuid: String, xs: List[T])
        (implicit conn: js.Connection): Box[List[T]] = {
    var xsWithIds = Debate.assignIdsTo(xs)
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
                                    RELPA, TEXT, MARKUP, WHEERE)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """
      // Keep in mind that Oracle converts "" to null.
      val commonVals = Nil // p.loginId::pageSno::Nil
      x match {
        case p: Post =>
          db.update(insertIntoActions, commonVals:::List(
            p.loginId, pageSno, p.id, p.ctime, _toFlag(p.tyype),
            p.parent, e2n(p.text), e2n(p.markup), e2n(p.where)))
        case r: Rating =>
          db.update(insertIntoActions, commonVals:::List(
            r.loginId, pageSno, r.id, r.ctime, "Rating", r.postId,
            NullVarchar, NullVarchar, NullVarchar))
          db.batchUpdate("""
            insert into DW1_PAGE_RATINGS(PAGE, PAID, TAG) values (?, ?, ?)
            """, r.tags.map(t => List(pageSno, r.id, t)))
        case e: Edit =>
          db.update(insertIntoActions, commonVals:::List(
            e.loginId, pageSno, e.id, e.ctime, "Edit",
            e.postId, e2n(e.text), e2n(e.newMarkup), NullVarchar))
        case a: EditApp =>
          db.update(insertIntoActions, commonVals:::List(
            a.loginId, pageSno, a.id, a.ctime, "EditApp",
            a.editId, e2n(a.result), NullVarchar, NullVarchar))
        case f: Flag =>
          db.update(insertIntoActions, commonVals:::List(
            f.loginId, pageSno, f.id, f.ctime, "Flag" + f.reason,
            f.postId, e2n(f.details), NullVarchar, NullVarchar))
        case d: Delete =>
          db.update(insertIntoActions, commonVals:::List(
            d.loginId, pageSno, d.id, d.ctime,
            "Del" + (if (d.wholeTree) "Tree" else "Post"),
            d.postId, e2n(d.reason), NullVarchar, NullVarchar))
        case x => unimplemented(
          "Saving this: "+ classNameOf(x) +" [error DwE38rkRF]")
      }
    }
    Full(xsWithIds)
  }

  def _dummyUserIdFor(identityId: String) = "-"+ identityId

  def _dummyUserFor(identity: IdentitySimple, emailNotfPrefs: EmailNotfPrefs,
                    id: String = null): User = {
    User(id = (if (id ne null) id else identity.userId),
      displayName = identity.name, email = identity.email,
      emailNotfPrefs = emailNotfPrefs,
      country = "",
      website = identity.website, isSuperAdmin = false)
  }

  def _toFlag(postType: PostType): String = postType match {
    case PostType.Text => "Post"
    case PostType.Publish => "Publ"
    case PostType.Meta => "Meta"
  }

  def _toPostType(flag: String): PostType = flag match {
    case "Post" => PostType.Text
    case "Publ" => PostType.Publish
    case "Meta" => PostType.Meta
    case x =>
      warnDbgDie("Bad PostType value: "+ safed(x) +
          " [error DwE0xcke215]")
      PostType.Text  // fallback to something with no side effects
                      // (except perhaps for a weird post appearing)
  }

  def _toPageStatus(pageStatusStr: String): PageStatus = pageStatusStr match {
    case "D" => PageStatus.Draft
    case "P" => PageStatus.Published
    case "X" => PageStatus.Deleted
    case x =>
      warnDbgDie("Bad page status: "+ safed(x) +" [error DwE0395k7]")
      PageStatus.Draft  // make it visible to admins only
  }

  def _toFlag(pageStatus: PageStatus): String = pageStatus match {
    case PageStatus.Draft => "D"
    case PageStatus.Published => "P"
    case PageStatus.Deleted => "X"
    case x =>
      warnDbgDie("Bad PageStatus: "+ safed(x) +" [error DwE5k2eI5]")
      "D"  // make it visible to admins only
  }

  def _toFlag(prefs: EmailNotfPrefs): String = prefs match {
    case EmailNotfPrefs.Receive => "R"
    case EmailNotfPrefs.DontReceive => "N"
    case EmailNotfPrefs.ForbiddenForever => "F"
    case x =>
      warnDbgDie("Bad EmailNotfPrefs value: "+ safed(x) +
          " [error DwE0EH43k8]")
      "N"  // fallback to no email
  }

  def _toEmailNotfs(flag: String): EmailNotfPrefs = flag match {
    case null =>
      // Don't send email unless the user has actively choosen to
      // receive emails (the user has made no choice in this case).
      EmailNotfPrefs.DontReceive
    case "R" => EmailNotfPrefs.Receive
    case "N" => EmailNotfPrefs.DontReceive
    case "F" => EmailNotfPrefs.ForbiddenForever
    case x =>
      warnDbgDie("Bad EMAIL_NOTFS: "+ safed(x) +" [error DwE6ie53k011]")
      EmailNotfPrefs.DontReceive
  }

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
