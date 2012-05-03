/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.Dao._
import com.debiki.v0.EmailNotfPrefs.EmailNotfPrefs
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import scala.collection.{mutable => mut}
import RelDb.pimpOptionWithNullVarchar
import RelDbUtil._
import collection.mutable.StringBuilder


class RelDbSystemDaoSpi(val db: RelDb) extends SystemDaoSpi {
  // COULD serialize access, per page?

  import RelDb._

  def close() { db.close() }

  def checkRepoVersion() = Some("0.0.2")

  def secretSalt(): String = "9KsAyFqw_"

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
    })

    usersByTenantAndId
  }


  def createTenant(name: String): Tenant = {
    db.transaction { implicit connection =>
      val tenantId = db.nextSeqNo("DW1_TENANTS_ID").toString
      db.update("""
          insert into DW1_TENANTS (ID, NAME)
          values (?, ?)
          """, List(tenantId, name))
      Tenant(id = tenantId, name = name, hosts = Nil)
    }
  }


  def loadTenants(tenantIds: Seq[String]): Seq[Tenant] = {
    // For now, load only 1 tenant.
    require(tenantIds.length == 1)

    var hostsByTenantId = Map[String, List[TenantHost]]().withDefaultValue(Nil)
    db.queryAtnms("""
        select TENANT, HOST, CANONICAL, HTTPS from DW1_TENANT_HOSTS
        where TENANT = ?  -- in the future: where TENANT in (...)
        """,
      List(tenantIds.head),
      rs => {
        while (rs.next) {
          val tenantId = rs.getString("TENANT")
          var hosts = hostsByTenantId(tenantId)
          hosts ::= TenantHost(
             address = rs.getString("HOST"),
             role = _toTenantHostRole(rs.getString("CANONICAL")),
             https = _toTenantHostHttps(rs.getString("HTTPS")))
          hostsByTenantId = hostsByTenantId.updated(tenantId, hosts)
        }
      })

    var tenants = List[Tenant]()
    db.queryAtnms("""
        select ID, NAME from DW1_TENANTS where ID = ?
        """,
        List(tenantIds.head),
        rs => {
      while (rs.next) {
        val tenantId = rs.getString("ID")
        val hosts = hostsByTenantId(tenantId)
        tenants ::= Tenant(tenantId, name = rs.getString("NAME"), hosts = hosts)
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


  def loadNotfsToMailOut(delayInMinutes: Int, numToLoad: Int): NotfsToMail =
    loadNotfsImpl(numToLoad, None, delayMinsOpt = Some(delayInMinutes))


  /**
   * Specify:
   * numToLoad + delayMinsOpt --> loads notfs to mail out, for all tenants
   * tenantIdOpt + userIdOpt --> loads that user's notfs
   * tenantIdOpt + emailIdOpt --> loads a single email and notf
   * @return
   */
  //private  [this package]
  def loadNotfsImpl(numToLoad: Int, tenantIdOpt: Option[String] = None,
        delayMinsOpt: Option[Int] = None, userIdOpt: Option[String] = None,
        emailIdOpt: Option[String] = None)
        : NotfsToMail = {

    require(delayMinsOpt.isEmpty || userIdOpt.isEmpty)
    require(delayMinsOpt.isEmpty || emailIdOpt.isEmpty)
    require(userIdOpt.isEmpty || emailIdOpt.isEmpty)
    require(delayMinsOpt.isDefined != tenantIdOpt.isDefined)
    require(!userIdOpt.isDefined || tenantIdOpt.isDefined)
    require(!emailIdOpt.isDefined || tenantIdOpt.isDefined)
    require(numToLoad > 0)
    require(emailIdOpt.isEmpty || numToLoad == 1)
    // When loading email addrs, an SQL in list is used, but
    // Oracle limits the max in list size to 1000. As a stupid workaround,
    // don't load more than 1000 notifications at a time.
    illArgErrIf3(numToLoad >= 1000, "DwE903kI23", "Too long SQL in-list")

    val baseQuery = """
       select
         TENANT, CTIME, PAGE_ID, PAGE_TITLE,
         RCPT_ID_SIMPLE, RCPT_ROLE_ID,
         EVENT_TYPE, EVENT_PGA, TARGET_PGA, RCPT_PGA,
         RCPT_USER_DISP_NAME, EVENT_USER_DISP_NAME, TARGET_USER_DISP_NAME,
         STATUS, EMAIL_STATUS, EMAIL_SENT, EMAIL_LINK_CLICKED, DEBUG
       from DW1_NOTFS_PAGE_ACTIONS
       where """

    val (whereOrderBy, vals) = (userIdOpt, emailIdOpt) match {
      case (Some(uid), None) =>
        var whereOrderBy =
           "TENANT = ? and "+ (
           if (uid startsWith "-") "RCPT_ID_SIMPLE = ?"
           else "RCPT_ROLE_ID = ?"
           ) +" order by CTIME desc"
        // IdentitySimple user ids start with '-'.
        val uidNoDash = uid.dropWhile(_ == '-')
        val vals = List(tenantIdOpt.get, uidNoDash)
        (whereOrderBy, vals)
      case (None, Some(emailId)) =>
        val whereOrderBy = "TENANT = ? and EMAIL_SENT = ?"
        val vals = tenantIdOpt.get::emailId::Nil
        (whereOrderBy, vals)
      case (None, None) =>
        // Load notfs with emails pending, for all tenants.
        val whereOrderBy =
           "EMAIL_STATUS = 'P' and CTIME <= ? order by CTIME asc"
        val nowInMillis = (new ju.Date).getTime
        val someMinsAgo =
           new ju.Date(nowInMillis - delayMinsOpt.get * 60 * 1000)
        val vals = someMinsAgo::Nil
        (whereOrderBy, vals)
      case _ =>
        assErr("DwE093RI3")
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
          targetUserDispName = Option(rs.getString("TARGET_USER_DISP_NAME")),
          emailPending = rs.getString("EMAIL_STATUS") == "P",
          emailId = Option(rs.getString("EMAIL_SENT")),
          debug = Option(rs.getString("DEBUG")))

        // Add notf to the list of all notifications for tenantId.
        val notfsForTenant: List[NotfOfPageAction] = notfsByTenant(tenantId)
        notfsByTenant = notfsByTenant + (tenantId -> (notf::notfsForTenant))
      }
    })

    val userIdsByTenant: Map[String, List[String]] =
       notfsByTenant.mapValues(_.map(_.recipientUserId))

    val usersByTenantAndId: Map[(String, String), User] =
      loadUsers(userIdsByTenant)

    NotfsToMail(notfsByTenant, usersByTenantAndId)
  }

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list

