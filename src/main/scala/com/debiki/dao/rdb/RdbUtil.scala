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


  def userIdAndColumnFor(userId: UserId) = {
    if (User.isRoleId(userId))
      (userId, "ROLE_ID")
    else
      (userId.drop(1), "GUEST_ID")
  }


  def ActionSelectListItems =
    "a.POST_ID, a.MULTIREPLY, a.PAID, a.GUEST_ID, a.ROLE_ID, a.TIME, a.TYPE, a.RELPA, " +
     "a.TEXT, a.WHEERE, a.LONG_VALUE, a.IP, " +
     "a.BROWSER_ID_COOKIE, a.BROWSER_FINGERPRINT," +
     "a.APPROVAL, a.AUTO_APPLICATION, a.DELETED_AT, a.DELETED_BY_ID"

  def _Action(rs: js.ResultSet): RawPostAction[_] = {
    val postId = rs.getInt("POST_ID")
    val id = rs.getInt("PAID")

    val anyIp = Option(rs.getString("IP"))
    val isSystemUser = anyIp.isEmpty
    val ip = anyIp getOrElse SystemUser.Ip

    val userId: UserId = {
      val anyGuestId = Option(rs.getString("GUEST_ID"))
      val anyRoleId = Option(rs.getString("ROLE_ID"))
      (anyGuestId, anyRoleId) match {
        case (Some(guestId), None) =>
          dieIf(guestId == "1", "DwE8PKB0", "Gusest id matches system user id")
          "-" + guestId
        case (None, Some(roleId)) => roleId
        case (None, None) =>
          if (isSystemUser) SystemUser.User.id
          else UnknownUser.Id
        case _ => assErr("DwE7390FU3")
      }
    }

    val time = ts2d(rs.getTimestamp("TIME"))
    val typee = rs.getString("TYPE")
    val relpa = getOptionalIntNoneNot0(rs, "RELPA")
    val text_? = rs.getString("TEXT")
    val where_? = rs.getString("WHEERE")
    val longValue_? = rs.getLong("LONG_VALUE")
    val anyBrowserIdCookie = Option(rs.getString("BROWSER_ID_COOKIE"))
    val browserFingerprint = Option(rs.getInt("BROWSER_FINGERPRINT")) getOrElse 0
    val approval = _toAutoApproval(rs.getString("APPROVAL"))
    val editAutoApplied = rs.getString("AUTO_APPLICATION") == "A"
    val deletedAt = ts2o(rs.getTimestamp("DELETED_AT"))
    val deletedById = Option(rs.getString("DELETED_BY_ID"))

    val userIdData = UserIdData(
      userId = userId,
      ip = ip,
      browserIdCookie = anyBrowserIdCookie,
      browserFingerprint = browserFingerprint)

    def buildAction(payload: PostActionPayload) =
      RawPostAction(id, time, payload, postId = postId, userIdData = userIdData,
        deletedAt = deletedAt, deletedById = deletedById)

    def details = o"""action id: ${Option(id)}, post id: ${Option(postId)},
      target: $relpa, user id: $userId"""
    assErrIf(postId < 0, "DwE5YQ08", s"POST_ID is < 0, details: $details")

    // (This whole match-case will go away when I unify all types
    // into CreatePostAction?)  ...
    val PAP = PostActionPayload
    val action = typee match {
      case "Post" =>
        buildAction(PAP.CreatePost(parentPostId = relpa,
          multireplyPostIds = fromDbMultireply(rs.getString("MULTIREPLY")), text = n2e(text_?),
          where = Option(where_?), approval = approval))
      case "Edit" =>
        buildAction(PAP.EditPost(n2e(text_?),
          autoApplied = editAutoApplied, approval = approval))
      case "EditApp" =>
        val editId = relpa getOrElse throwBadDatabaseData(
          "DwE26UF0", s"Edit id missing for edit app `$id', post `$postId', site `?'")
        buildAction(PAP.EditApp(editId = editId, approval = approval))
      case "VoteLike" =>
        buildAction(PAP.VoteLike)
      case "VoteWrong" =>
        buildAction(PAP.VoteWrong)
      case "VoteOffTopic" =>
        buildAction(PAP.VoteOffTopic)
      case flag if flag startsWith "Flag" =>
        val typeStr = flag drop 4 // drop "Flag"
        val tyype = FlagType withName typeStr
        buildAction(PAP.Flag(tyype = tyype, reason = n2e(text_?)))
      case "ClearFlags" =>
        buildAction(PAP.ClearFlags)
      case "DelPost" =>
        buildAction(PAP.DeletePost(clearFlags = false))
      case "DelPostClearFlags" =>
        buildAction(PAP.DeletePost(clearFlags = true))
      case "DelTree" =>
        buildAction(PAP.DeleteTree)
      case "HidePostClearFlags" =>
        buildAction(PAP.HidePostClearFlags)
      case "RejectDeleteEdits" =>
        buildAction(PAP.RejectEdits(deleteEdits = true))
      case "RejectKeepEdits" =>
        buildAction(PAP.RejectEdits(deleteEdits = false))
      case "Aprv" =>
        buildAction(PAP.ApprovePost(approval getOrDie "DwE2UGX0"))
      case "CloseTree" => buildAction(PostActionPayload.CloseTree)
      case "PinAtPos" =>
        buildAction(PAP.PinPostAtPosition(longValue_?.toInt))
      case x =>
        val anyHidingAction = parseCollapsingAction(x)
        anyHidingAction match {
          case Some(hidingPayload) =>
            buildAction(hidingPayload)
          case _ =>
            assErr("DwEY8k3B", s"Bad DW1_ACTIONS.TYPE: `$typee', details: $details")
        }
    }
    action
  }


  def parseCollapsingAction(text: String): Option[PostActionPayload.CollapseSomething] =
    Some(text match {
      case "CollapsePost" => PostActionPayload.CollapsePost
      case "CollapseTree" => PostActionPayload.CollapseTree
      case _ => return None
    })


  def toActionTypeStr(voteType: PostActionPayload.Vote): String = voteType match {
    case PostActionPayload.VoteLike => "VoteLike"
    case PostActionPayload.VoteWrong => "VoteWrong"
    case PostActionPayload.VoteOffTopic => "VoteOffTopic"
  }


  def _dummyUserIdFor(identityId: String) = {
    dieIf(identityId == "1", "DwE0GKf2", "Identity id matches system user id")
    s"-$identityId"
  }


  val _UserSelectListItems =
    // (These u_* item names are relied on e.g. by RdbSystemDao.loadUsers.)
    """u.SNO u_id,
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


  def _User(rs: js.ResultSet) =
    User(
      // Use dn2e not n2e. ((So works if joined w/ DW1_IDS_SIMPLE, which
      // uses '-' instead of null to indicate absence of email address etc.
      // See usage of this function in RdbSystemDao.loadUsers(). ))
      id = rs.getString("u_id"),
      displayName = dn2e(rs.getString("u_disp_name")),
      username = Option(rs.getString("u_username")),
      createdAt = ts2o(rs.getTimestamp("u_created_at")),
      email = dn2e(rs.getString("u_email")),
      emailNotfPrefs = _toEmailNotfs(rs.getString("u_email_notfs")),
      emailVerifiedAt = ts2o(rs.getTimestamp("u_email_verified_at")),
      passwordHash = Option(rs.getString("u_password_hash")),
      country = dn2e(rs.getString("u_country")),
      website = dn2e(rs.getString("u_website")),
      isAdmin = rs.getString("u_superadmin") == "T",
      isOwner = rs.getString("u_is_owner") == "T")


  def _PagePath(resultSet: js.ResultSet, tenantId: String,
        pageId: Option[Option[String]] = None) =
    PagePath(
      tenantId = tenantId,
      folder = resultSet.getString("PARENT_FOLDER"),
      pageId = pageId getOrElse Some(resultSet.getString("PAGE_ID")),
      showId = resultSet.getString("SHOW_ID") == "T",
      pageSlug = d2e(resultSet.getString("PAGE_SLUG")))


  val _PageMetaSelectListItems = i"""
      |g.CDATI,
      |g.MDATI,
      |g.PUBL_DATI,
      |g.SGFNT_MDATI,
      |g.PAGE_ROLE,
      |g.PARENT_PAGE_ID,
      |g.EMBEDDING_PAGE_URL,
      |g.CACHED_TITLE,
      |g.CACHED_AUTHOR_DISPLAY_NAME,
      |g.CACHED_AUTHOR_USER_ID,
      |g.CACHED_NUM_POSTERS,
      |g.CACHED_NUM_ACTIONS,
      |g.CACHED_NUM_LIKES,
      |g.CACHED_NUM_WRONGS,
      |g.CACHED_NUM_POSTS_DELETED,
      |g.CACHED_NUM_REPLIES_VISIBLE,
      |g.CACHED_NUM_POSTS_TO_REVIEW,
      |g.CACHED_LAST_VISIBLE_POST_DATI,
      |g.CACHED_NUM_CHILD_PAGES
      |"""


  def _PageMeta(resultSet: js.ResultSet, pageId: String = null) = {
    PageMeta(
      pageId = if (pageId ne null) pageId else
          unimplemented, // wrong column name: resultSet.getString("PAGE_ID"),
      pageRole = _toPageRole(resultSet.getString("PAGE_ROLE")),
      parentPageId = Option(resultSet.getString("PARENT_PAGE_ID")),
      embeddingPageUrl = Option(resultSet.getString("EMBEDDING_PAGE_URL")),
      creationDati = ts2d(resultSet.getTimestamp("CDATI")),
      modDati = ts2d(resultSet.getTimestamp("MDATI")),
      pubDati = Option(ts2d(resultSet.getTimestamp("PUBL_DATI"))),
      sgfntModDati = Option(ts2d(resultSet.getTimestamp("SGFNT_MDATI"))),
      cachedTitle = Option(resultSet.getString(("CACHED_TITLE"))),
      cachedAuthorDispName = n2e(resultSet.getString("CACHED_AUTHOR_DISPLAY_NAME")),
      cachedAuthorUserId = n2e(resultSet.getString("CACHED_AUTHOR_USER_ID")),
      cachedNumPosters = n20(resultSet.getInt("CACHED_NUM_POSTERS")),
      cachedNumActions = n20(resultSet.getInt("CACHED_NUM_ACTIONS")),
      cachedNumLikes = n20(resultSet.getInt("CACHED_NUM_LIKES")),
      cachedNumWrongs = n20(resultSet.getInt("CACHED_NUM_WRONGS")),
      cachedNumPostsDeleted = n20(resultSet.getInt("CACHED_NUM_POSTS_DELETED")),
      cachedNumRepliesVisible = n20(resultSet.getInt("CACHED_NUM_REPLIES_VISIBLE")),
      cachedNumPostsToReview = n20(resultSet.getInt("CACHED_NUM_POSTS_TO_REVIEW")),
      cachedLastVisiblePostDati =
        Option(ts2d(resultSet.getTimestamp("CACHED_LAST_VISIBLE_POST_DATI"))),
      cachedNumChildPages = resultSet.getInt("CACHED_NUM_CHILD_PAGES"))
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

  def _toAutoApproval(dbVal: String): Option[Approval] = dbVal match {
    case null => None
    case "P" => Some(Approval.Preliminary)
    case "W" => Some(Approval.WellBehavedUser)
    case "A" => Some(Approval.AuthoritativeUser)
  }


  def _toDbVal(approval: Option[Approval]): AnyRef = approval match {
    case None => NullVarchar
    case Some(Approval.Preliminary) => "P"
    case Some(Approval.WellBehavedUser) => "W"
    case Some(Approval.AuthoritativeUser) => "A"
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


  /** Adds a can be Empty Prefix.
   *
   * Oracle converts the empty string to NULL, so prefix strings that might
   * be empty with a magic value, and remove it when reading data from
   * the db.
   */
  //private def _aep(str: String) = "-"+ str

  /** Removes a can be Empty Prefix. */
  //private def _rep(str: String) = str drop 1

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
