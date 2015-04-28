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
import com.debiki.core.EmailNotfPrefs.EmailNotfPrefs
import com.debiki.core.Prelude._
import java.{sql => js}
import scala.{collection => col}
import Rdb._


object RdbUtil {


  /**
   * I've included too many chars, I think, to play safe.
   * E.g. `=` and "` and `'` and '`' are no regex chars?
   */
  val MagicRegexChars = """!"#$%&'()*+,.:;<=>?@[\]^`{|}~"""


  /**
   * Use like so: "select ... where X in ("+ makeInListFor(xs) +")"
   */
  def makeInListFor(values: Seq[_]): String =
    values.map((x: Any) => "?").mkString(",")

  /** In PostgreSQL, one can order by id like so:
    *   select ... from ... where (id in (3, 4, 1, 2))
    *   order by id=3 desc, id=4 desc, id=1 desc
    * see http://stackoverflow.com/a/9475755/694469
    *
    * Returns "id=? desc, id=? desc, ...",
    * and simply "<field>" if `ids` is empty.
    */
  def makeOrderByListFor(field: String, ids: Seq[_]): String = {
    if (ids.isEmpty)
      return field

    val sb = StringBuilder.newBuilder
    for (id <- ids) {
      if (sb.nonEmpty) sb.append(", ")
      sb.append(s"$field = ? desc")
    }
    sb.toString
  }

  /** `rs.getInt` returns 0 instead of null.
   */
  def getOptionalIntNoneNot0(rs: js.ResultSet, column: String): Option[Int] = {
    val i = rs.getInt(column)
    if (i == 0) None
    else Some(i)
  }


  val UserSelectListItemsNoGuests =
    """u.USER_ID u_id,
      |u.DISPLAY_NAME u_disp_name,
      |u.USERNAME u_username,
      |u.CREATED_AT u_created_at,
      |u.EMAIL u_email,
      |u.EMAIL_NOTFS u_email_notfs,
      |u.EMAIL_VERIFIED_AT u_email_verified_at,
      |u.PASSWORD_HASH u_password_hash,
      |u.COUNTRY u_country,
      |u.WEBSITE u_website,
      |u.SUPERADMIN u_superadmin,
      |u.IS_OWNER u_is_owner""".stripMargin

  val UserSelectListItemsWithGuests =
    s"$UserSelectListItemsNoGuests, e.EMAIL_NOTFS g_email_notfs"

  def _User(rs: js.ResultSet) = {
    val userId = rs.getInt("u_id")
    val emailNotfPrefs = {
      if (User.isGuestId(userId))
        _toEmailNotfs(rs.getString("g_email_notfs"))
      else
        _toEmailNotfs(rs.getString("u_email_notfs"))
    }
    User(
      // Use dn2e not n2e. ((So works if joined w/ DW1_IDS_SIMPLE, which
      // uses '-' instead of null to indicate absence of email address etc.
      // See usage of this function in RdbSystemDao.loadUsers(). ))
      id = userId,
      displayName = dn2e(rs.getString("u_disp_name")),
      username = Option(rs.getString("u_username")),
      createdAt = ts2o(rs.getTimestamp("u_created_at")),
      email = dn2e(rs.getString("u_email")),
      emailNotfPrefs = emailNotfPrefs,
      emailVerifiedAt = ts2o(rs.getTimestamp("u_email_verified_at")),
      passwordHash = Option(rs.getString("u_password_hash")),
      country = dn2e(rs.getString("u_country")),
      website = dn2e(rs.getString("u_website")),
      isAdmin = rs.getString("u_superadmin") == "T",
      isOwner = rs.getString("u_is_owner") == "T")
  }


  def _PagePath(resultSet: js.ResultSet, tenantId: String,
        pageId: Option[Option[String]] = None) =
    PagePath(
      tenantId = tenantId,
      folder = resultSet.getString("PARENT_FOLDER"),
      pageId = pageId getOrElse Some(resultSet.getString("PAGE_ID")),
      showId = resultSet.getString("SHOW_ID") == "T",
      pageSlug = d2e(resultSet.getString("PAGE_SLUG")))


  val _PageMetaSelectListItems = i"""
      |g.CREATED_AT,
      |g.UPDATED_AT,
      |g.PUBLISHED_AT,
      |g.BUMPED_AT,
      |g.PAGE_ROLE,
      |g.PARENT_PAGE_ID,
      |g.EMBEDDING_PAGE_URL,
      |g.AUTHOR_ID,
      |g.NUM_LIKES,
      |g.NUM_WRONGS,
      |g.NUM_REPLIES_VISIBLE,
      |g.NUM_REPLIES_TOTAL,
      |g.NUM_CHILD_PAGES
      |"""


  def _PageMeta(resultSet: js.ResultSet, pageId: String = null) = {
    PageMeta(
      pageId = if (pageId ne null) pageId else resultSet.getString("PAGE_ID"),
      pageRole = _toPageRole(resultSet.getString("PAGE_ROLE")),
      parentPageId = Option(resultSet.getString("PARENT_PAGE_ID")),
      embeddingPageUrl = Option(resultSet.getString("EMBEDDING_PAGE_URL")),
      createdAt = ts2d(resultSet.getTimestamp("CREATED_AT")),
      updatedAt = ts2d(resultSet.getTimestamp("UPDATED_AT")),
      publishedAt = Option(ts2d(resultSet.getTimestamp("PUBLISHED_AT"))),
      bumpedAt = Option(ts2d(resultSet.getTimestamp("BUMPED_AT"))),
      authorId = resultSet.getInt("AUTHOR_ID"),
      numLikes = n20(resultSet.getInt("NUM_LIKES")),
      numWrongs = n20(resultSet.getInt("NUM_WRONGS")),
      numRepliesVisible = n20(resultSet.getInt("NUM_REPLIES_VISIBLE")),
      numRepliesTotal = n20(resultSet.getInt("NUM_REPLIES_TOTAL")),
      numChildPages = resultSet.getInt("NUM_CHILD_PAGES"))
  }


  def _ResourceUse(rs: js.ResultSet) =
    ResourceUse(
      numGuests = rs.getInt("NUM_GUESTS"),
      numIdentities = rs.getInt("NUM_IDENTITIES"),
      numRoles = rs.getInt("NUM_ROLES"),
      numRoleSettings = rs.getInt("NUM_ROLE_SETTINGS"),
      numPages = rs.getInt("NUM_PAGES"),
      numPosts = rs.getInt("NUM_POSTS"),
      numPostTextBytes = rs.getLong("NUM_POST_TEXT_BYTES"),
      numPostsRead = rs.getLong("NUM_POSTS_READ"),
      numActions = rs.getInt("NUM_ACTIONS"),
      numNotfs = rs.getInt("NUM_NOTFS"),
      numEmailsSent = rs.getInt("NUM_EMAILS_SENT"))


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


  def toDbMultireply(postIds: Set[PostId]) = {
    if (postIds.isEmpty) NullVarchar
    else postIds.mkString(",")
  }

  def fromDbMultireply(postIdsCommaSeparated: String): Set[PostId] = {
    if (postIdsCommaSeparated == null) Set[PostId]()
    else postIdsCommaSeparated.split(',').map(_.toInt).toSet
  }

  def _toPageStatus(pageStatusStr: String): PageStatus = pageStatusStr match {
    case "D" => PageStatus.Draft
    case "P" => PageStatus.Published
    case "X" => PageStatus.Deleted
    case x =>
      warnDbgDie("Bad page status: "+ safed(x) +" [error DwE0395k7]")
      PageStatus.Draft  // make it visible to admins only
  }


  def _toPageRole(pageRoleString: String): PageRole = pageRoleString match {
    case null => PageRole.WebPage
    case "H" => PageRole.HomePage
    case "P" => PageRole.WebPage
    case "EC" => PageRole.EmbeddedComments
    case "B" => PageRole.Blog
    case "BP" => PageRole.BlogPost
    case "F" => PageRole.Forum
    case "FC" => PageRole.ForumCategory
    case "FT" => PageRole.ForumTopic
    case "W" => PageRole.WikiMainPage
    case "WP" => PageRole.WikiPage
    case "C" => PageRole.Code
    case "SP" => PageRole.SpecialContent
    case _ =>
      warnDbgDie(
        "Bad page role string: "+ pageRoleString +" [error DwE390KW8]")
      PageRole.WebPage
  }


  def _pageRoleToSql(pageRole: PageRole): AnyRef = pageRole match {
    case PageRole.HomePage => "H"
    case PageRole.WebPage => "P"
    case PageRole.EmbeddedComments => "EC"
    case PageRole.Blog => "B"
    case PageRole.BlogPost => "BP"
    case PageRole.Forum => "F"
    case PageRole.ForumCategory => "FC"
    case PageRole.ForumTopic => "FT"
    case PageRole.WikiMainPage => "W"
    case PageRole.WikiPage => "WP"
    case PageRole.Code => "C"
    case PageRole.SpecialContent => "SP"
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



  // COULD do this:
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

