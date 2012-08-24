/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.EmailNotfPrefs.EmailNotfPrefs
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import scala.{collection => col}
import RelDb._


object RelDbUtil {


  def _Action(rs: js.ResultSet, ratingTags: col.Map[String, List[String]])
        : Action = {
    val id = rs.getString("PAID")
    val loginSno = rs.getLong("LOGIN").toString
    val time = ts2d(rs.getTimestamp("TIME"))
    val typee = rs.getString("TYPE")
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
      case x =>
        assErr("DwEY8k3B", "Bad DW1_ACTIONS.TYPE: "+ safed(typee))
    }
    action
  }


  def _dummyUserIdFor(identityId: String) = "-"+ identityId


  def _dummyUserFor(identity: IdentitySimple, emailNotfPrefs: EmailNotfPrefs,
                    id: String = null): User = {
    User(id = (if (id ne null) id else identity.userId),
      displayName = identity.name, email = identity.email,
      emailNotfPrefs = emailNotfPrefs,
      country = "",
      website = identity.website, isSuperAdmin = false, isOwner = false)
  }


  val _UserSelectListItems =
    // (These u_* item names are relied on e.g. by RelDbSystemDaoSpi.loadUsers.)
    """u.SNO u_id,
      |u.DISPLAY_NAME u_disp_name,
      |u.EMAIL u_email,
      |u.EMAIL_NOTFS u_email_notfs,
      |u.COUNTRY u_country,
      |u.WEBSITE u_website,
      |u.SUPERADMIN u_superadmin,
      |u.IS_OWNER u_is_owner""".stripMargin


  def _User(rs: js.ResultSet) =
    User(
      // Use dn2e not n2e. ((So works if joined w/ DW1_IDS_SIMPLE, which
      // uses '-' instead of null to indicate absence of email address etc.
      // See usage of this function in RelDbSystemDaoSpi.loadUsers(). ))
      id = rs.getString("u_id"),
      displayName = dn2e(rs.getString("u_disp_name")),
      email = dn2e(rs.getString("u_email")),
      emailNotfPrefs = _toEmailNotfs(rs.getString("u_email_notfs")),
      country = dn2e(rs.getString("u_country")),
      website = dn2e(rs.getString("u_website")),
      isSuperAdmin = rs.getString("u_superadmin") == "T",
      isOwner = rs.getString("u_is_owner") == "T")


  def _PagePath(resultSet: js.ResultSet, tenantId: String) =
    PagePath(
      tenantId = tenantId,
      folder = resultSet.getString("PARENT_FOLDER"),
      pageId = Some(resultSet.getString("PAGE_ID")),
      showId = resultSet.getString("SHOW_ID") == "T",
      pageSlug = d2e(resultSet.getString("PAGE_SLUG")))


  def _QuotaConsumer(rs: js.ResultSet) = {
    val tenantId = rs.getString("TENANT")
    val ip = rs.getString("IP")
    val roleId = rs.getString("ROLE_ID")
    if (tenantId eq null) {
      assert(roleId eq null)
      QuotaConsumer.GlobalIp(ip)
    }
    else if (ip ne null) {
      QuotaConsumer.PerTenantIp(tenantId = tenantId, ip = ip)
    }
    else if (roleId eq null) {
      QuotaConsumer.Tenant(tenantId)
    }
    else if (roleId ne null) {
      QuotaConsumer.Role(tenantId = tenantId, roleId = roleId)
    }
    else {
      assErr("DwE021kJQ2")
    }
  }


  def _QuotaUse(rs: js.ResultSet) = QuotaUse(
    paid = rs.getLong("QUOTA_USED_PAID"),
    free = rs.getLong("QUOTA_USED_FREE"),
    freeload = rs.getLong("QUOTA_USED_FREELOADED"))


  def _QuotaUseLimits(rs: js.ResultSet) = QuotaUse(
    paid = rs.getLong("QUOTA_LIMIT_PAID"),
    free = rs.getLong("QUOTA_LIMIT_FREE"),
    freeload = rs.getLong("QUOTA_LIMIT_FREELOAD"))


  def _ResourceUse(rs: js.ResultSet) =
    ResourceUse(
      numLogins = rs.getInt("NUM_LOGINS"),
      numIdsUnau = rs.getInt("NUM_IDS_UNAU"),
      numIdsAu = rs.getInt("NUM_IDS_AU"),
      numRoles = rs.getInt("NUM_ROLES"),
      numPages = rs.getInt("NUM_PAGES"),
      numActions = rs.getInt("NUM_ACTIONS"),
      numActionTextBytes = rs.getLong("NUM_ACTION_TEXT_BYTES"),
      numNotfs = rs.getInt("NUM_NOTFS"),
      numEmailsOut = rs.getInt("NUM_EMAILS_OUT"),
      numDbReqsRead = rs.getLong("NUM_DB_REQS_READ"),
      numDbReqsWrite = rs.getLong("NUM_DB_REQS_WRITE"))


  def _toTenantHostRole(roleStr: String) = roleStr match {
    case "C" => TenantHost.RoleCanonical
    case "R" => TenantHost.RoleRedirect
    case "L" => TenantHost.RoleLink
    case "D" => TenantHost.RoleDuplicate
  }


  def _toTenantHostHttps(httpsStr: String) = httpsStr match {
    case "R" => TenantHost.HttpsRequired
    case "A" => TenantHost.HttpsAllowed
    case "N" => TenantHost.HttpsNone
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


  def _toFlag(prefs: EmailNotfPrefs): AnyRef = prefs match {
    case EmailNotfPrefs.Unspecified => NullVarchar
    case EmailNotfPrefs.Receive => "R"
    case EmailNotfPrefs.DontReceive => "N"
    case EmailNotfPrefs.ForbiddenForever => "F"
    case x =>
      warnDbgDie("Bad EmailNotfPrefs value: "+ safed(x) +
          " [error DwE0EH43k8]")
      NullVarchar // fallback to Unspecified
  }


  def _toEmailNotfs(flag: String): EmailNotfPrefs = flag match {
    case null => EmailNotfPrefs.Unspecified
    case "R" => EmailNotfPrefs.Receive
    case "N" => EmailNotfPrefs.DontReceive
    case "F" => EmailNotfPrefs.ForbiddenForever
    case x =>
      warnDbgDie("Bad EMAIL_NOTFS: "+ safed(x) +" [error DwE6ie53k011]")
      EmailNotfPrefs.Unspecified
  }


  /**
   * Returns e.g.:
   * ( "(PARENT_FOLDER = ?) or (PARENT_FOLDER like ?)", List(/some/, /paths/) )
   */
  def _pageRangeToSql(pageRange: PathRanges, columnPrefix: String = "")
        : (String, List[String]) = {
    var sql = new StringBuilder
    var values = List[String]()

    for (folder <- pageRange.folders) {
      if (sql nonEmpty) sql append " or "
      sql.append("("+ columnPrefix + "PARENT_FOLDER = ?)")
      values ::= folder
    }

    for (folder <- pageRange.trees) {
      if (sql nonEmpty) sql append " or "
      sql.append("("+ columnPrefix + "PARENT_FOLDER like ?)")
      values ::= folder +"%"
    }

    (sql.toString, values)
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
*/

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
