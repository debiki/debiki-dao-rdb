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
import com.debiki.core.User.isGuestId
import java.{sql => js, util => ju}
import scala.collection.immutable
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

  def makeInListFor(values: Iterable[_]): String = {
    // makeInListFor(Seq[_]), when given an Iterable, appended only one single "?"
    // instead of many, why? Anyway, instead:
    dieIf(values.isEmpty, "DwE7YME4")
    var result = "?"
    if (values.size >= 2) {
      result += ",?" * (values.size - 1)
    }
    result
  }

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

  def descOrAsc(orderBy: OrderBy) = orderBy match {
    case OrderBy.MostRecentFirst => "desc"
    case OrderBy.OldestFirst => "asc"
  }

  /** `rs.getInt` returns 0 instead of null.
   */
  @deprecated("use getOptionalInt instead", "now")
  def getOptionalIntNoneNot0(rs: js.ResultSet, column: String): Option[Int] = {
    val i = rs.getInt(column)
    if (rs.wasNull()) None
    else Some(i)
  }

  /** `rs.getBoolean` returns false instead of null.
    */
  def getOptionalBoolean(rs: js.ResultSet, column: String): Option[Boolean] = {
    val b = rs.getBoolean(column)
    if (rs.wasNull()) None
    else Some(b)
  }


  val InviteSelectListItems = i"""
      |site_id,
      |secret_key,
      |email_address,
      |created_by_id,
      |created_at,
      |accepted_at,
      |user_id,
      |deleted_at,
      |deleted_by_id,
      |invalidated_at
      |"""


  def getInvite(rs: js.ResultSet) = Invite(
    emailAddress = rs.getString("email_address"),
    secretKey = rs.getString("secret_key"),
    createdById = rs.getInt("created_by_id"),
    createdAt = getDate(rs, "created_at"),
    acceptedAt = getOptionalDate(rs, "accepted_at"),
    userId = getOptionalIntNoneNot0(rs, "user_id"),
    deletedAt = getOptionalDate(rs, "deleted_at"),
    deletedById = getOptionalIntNoneNot0(rs, "deleted_by_id"),
    invalidatedAt = getOptionalDate(rs, "invalidated_at"))


  val UserSelectListItemsNoGuests =
    """u.USER_ID u_id,
      |u.DISPLAY_NAME u_disp_name,
      |u.USERNAME u_username,
      |u.IS_APPROVED u_is_approved,
      |u.APPROVED_AT u_approved_at,
      |u.APPROVED_BY_ID u_approved_by_id,
      |u.SUSPENDED_TILL u_suspended_till,
      |u.EMAIL u_email,
      |u.EMAIL_NOTFS u_email_notfs,
      |u.EMAIL_VERIFIED_AT u_email_verified_at,
      |u.PASSWORD_HASH u_password_hash,
      |u.COUNTRY u_country,
      |u.WEBSITE u_website,
      |u.avatar_tiny_base_url,
      |u.avatar_tiny_hash_path,
      |u.avatar_small_base_url,
      |u.avatar_small_hash_path,
      |u.IS_OWNER u_is_owner,
      |u.IS_ADMIN u_is_admin,
      |u.IS_MODERATOR u_is_moderator""".stripMargin


  val UserSelectListItemsWithGuests =
    s"$UserSelectListItemsNoGuests, u.GUEST_COOKIE u_guest_cookie, e.EMAIL_NOTFS g_email_notfs"


  def _User(rs: js.ResultSet) = {
    val userId = rs.getInt("u_id")
    val emailNotfPrefs = {
      if (isGuestId(userId))
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
      guestCookie = isGuestId(userId) ? Option(rs.getString("u_guest_cookie")) | None,
      email = dn2e(rs.getString("u_email")),
      emailNotfPrefs = emailNotfPrefs,
      emailVerifiedAt = getOptionalDate(rs, "u_email_verified_at"),
      passwordHash = Option(rs.getString("u_password_hash")),
      country = dn2e(rs.getString("u_country")),
      website = dn2e(rs.getString("u_website")),
      tinyAvatar = getAnyUploadRef(rs, "avatar_tiny_base_url", "avatar_tiny_hash_path"),
      smallAvatar = getAnyUploadRef(rs, "avatar_small_base_url", "avatar_small_hash_path"),
      isApproved = getOptionalBoolean(rs, "u_is_approved"),
      suspendedTill = getOptionalDate(rs, "u_suspended_till"),
      isOwner = rs.getString("u_is_owner") == "T",
      isAdmin = rs.getString("u_is_admin") == "T",
      isModerator = rs.getBoolean("u_is_moderator"))
  }


  val CompleteUserSelectListItemsNoUserId = i"""
    |display_name,
    |email,
    |country,
    |website,
    |email_notfs,
    |is_owner,
    |is_admin,
    |is_moderator,
    |username,
    |email_verified_at,
    |created_at,
    |password_hash,
    |email_for_every_new_post,
    |avatar_tiny_base_url,
    |avatar_tiny_hash_path,
    |avatar_small_base_url,
    |avatar_small_hash_path,
    |avatar_medium_base_url,
    |avatar_medium_hash_path,
    |is_approved,
    |approved_at,
    |approved_by_id,
    |suspended_at,
    |suspended_till,
    |suspended_by_id,
    |suspended_reason
    """

  val CompleteUserSelectListItemsWithUserId =
    s"user_id, $CompleteUserSelectListItemsNoUserId"


  def getCompleteUser(rs: js.ResultSet, userId: Option[UserId] = None): CompleteUser = {
    val theUserId = userId getOrElse rs.getInt("user_id")
    dieIf(User.isGuestId(theUserId), "DwE6P4K3")
    CompleteUser(
      id = theUserId,
      fullName = dn2e(rs.getString("display_name")),
      username = rs.getString("username"),
      createdAt = getDate(rs, "created_at"),
      emailAddress = dn2e(rs.getString("email")),
      emailNotfPrefs = _toEmailNotfs(rs.getString("email_notfs")),
      emailVerifiedAt = getOptionalDate(rs, "email_verified_at"),
      emailForEveryNewPost = rs.getBoolean("email_for_every_new_post"),
      passwordHash = Option(rs.getString("password_hash")),
      tinyAvatar = getAnyUploadRef(rs, "avatar_tiny_base_url", "avatar_tiny_hash_path"),
      smallAvatar = getAnyUploadRef(rs, "avatar_small_base_url", "avatar_small_hash_path"),
      mediumAvatar = getAnyUploadRef(rs, "avatar_medium_base_url", "avatar_medium_hash_path"),
      country = dn2e(rs.getString("country")),
      website = dn2e(rs.getString("website")),
      isApproved = getOptionalBoolean(rs, "is_approved"),
      approvedAt = getOptionalDate(rs, "approved_at"),
      approvedById = getOptionalIntNoneNot0(rs, "approved_by_id"),
      suspendedAt = getOptionalDate(rs, "suspended_at"),
      suspendedTill = getOptionalDate(rs, "suspended_till"),
      suspendedById = getOptionalIntNoneNot0(rs, "suspended_by_id"),
      suspendedReason = Option(rs.getString("suspended_reason")),
      isOwner = rs.getString("is_owner") == "T",
      isAdmin = rs.getString("is_admin") == "T",
      isModerator = rs.getBoolean("is_moderator"))
  }


  def getAnyUploadRef(rs: js.ResultSet, basePathColumn: String, hashPathSuffixColumn: String)
        : Option[UploadRef] = {
    val basePath = Option(rs.getString(basePathColumn))
    val hashPathSuffix = Option(rs.getString(hashPathSuffixColumn))
    if (basePath.isEmpty && hashPathSuffix.isEmpty) {
      None
    }
    else if (basePath.isDefined && hashPathSuffix.isDefined) {
      Some(UploadRef(basePath.get, hashPathSuffix.get))
    }
    else {
      die("EdE03WMY3")
    }
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
      |g.version,
      |g.CREATED_AT,
      |g.UPDATED_AT,
      |g.PUBLISHED_AT,
      |g.BUMPED_AT,
      |g.LAST_REPLY_AT,
      |g.last_reply_by_id,
      |g.AUTHOR_ID,
      |g.frequent_poster_1_id,
      |g.frequent_poster_2_id,
      |g.frequent_poster_3_id,
      |g.PIN_ORDER,
      |g.PIN_WHERE,
      |g.PAGE_ROLE,
      |g.category_id,
      |g.EMBEDDING_PAGE_URL,
      |g.NUM_LIKES,
      |g.NUM_WRONGS,
      |g.NUM_BURY_VOTES,
      |g.NUM_UNWANTED_VOTES,
      |g.NUM_REPLIES_VISIBLE,
      |g.NUM_REPLIES_TOTAL,
      |g.NUM_OP_LIKE_VOTES,
      |g.NUM_OP_WRONG_VOTES,
      |g.NUM_OP_BURY_VOTES,
      |g.NUM_OP_UNWANTED_VOTES,
      |g.NUM_OP_REPLIES_VISIBLE,
      |g.answered_at,
      |g.ANSWER_POST_ID,
      |g.PLANNED_AT,
      |g.DONE_AT,
      |g.CLOSED_AT,
      |g.LOCKED_AT,
      |g.FROZEN_AT,
      |g.html_tag_css_classes,
      |g.NUM_CHILD_PAGES
      |"""


  def _PageMeta(resultSet: js.ResultSet, pageId: String = null) = {
    // We always write to bumped_at so order by queries work, but if in fact the page
    // hasn't been modified since it was created or published, it has not been bumped.
    var bumpedAt: Option[ju.Date] = Some(getDate(resultSet, "BUMPED_AT"))
    val createdAt = getDate(resultSet, "CREATED_AT")
    val publishedAt = getOptionalDate(resultSet, "PUBLISHED_AT")
    if (bumpedAt.get.getTime == createdAt.getTime ||
        publishedAt.exists(_.getTime == bumpedAt.get.getTime)) {
      bumpedAt = None
    }

    // 3 will do, don't need all 4.
    val frequentPoster1Id = getOptionalInt(resultSet, "frequent_poster_1_id")
    val frequentPoster2Id = getOptionalInt(resultSet, "frequent_poster_2_id")
    val frequentPoster3Id = getOptionalInt(resultSet, "frequent_poster_3_id")
    val frequentPosterIds = (frequentPoster1Id.toSeq ++ frequentPoster2Id.toSeq ++
      frequentPoster3Id.toSeq).to[immutable.Seq]

    PageMeta(
      pageId = if (pageId ne null) pageId else resultSet.getString("PAGE_ID"),
      pageRole = PageRole.fromInt(resultSet.getInt("PAGE_ROLE")) getOrElse PageRole.Discussion,
      version = resultSet.getInt("version"),
      categoryId = getOptionalIntNoneNot0(resultSet, "category_id"),
      embeddingPageUrl = Option(resultSet.getString("EMBEDDING_PAGE_URL")),
      createdAt = createdAt,
      updatedAt = getDate(resultSet, "UPDATED_AT"),
      publishedAt = publishedAt,
      bumpedAt = bumpedAt,
      lastReplyAt = getOptionalDate(resultSet, "LAST_REPLY_AT"),
      lastReplyById = getOptionalInt(resultSet, "last_reply_by_id"),
      authorId = resultSet.getInt("AUTHOR_ID"),
      frequentPosterIds = frequentPosterIds,
      pinOrder = getOptionalIntNoneNot0(resultSet, "PIN_ORDER"),
      pinWhere = getOptionalIntNoneNot0(resultSet, "PIN_WHERE").map(int =>
        PinPageWhere.fromInt(int).getOrElse(PinPageWhere.InCategory)),
      numLikes = n20(resultSet.getInt("NUM_LIKES")),
      numWrongs = n20(resultSet.getInt("NUM_WRONGS")),
      numBurys = n20(resultSet.getInt("NUM_BURY_VOTES")),
      numUnwanteds = n20(resultSet.getInt("NUM_UNWANTED_VOTES")),
      numRepliesVisible = n20(resultSet.getInt("NUM_REPLIES_VISIBLE")),
      numRepliesTotal = n20(resultSet.getInt("NUM_REPLIES_TOTAL")),
      numOrigPostLikeVotes = resultSet.getInt("num_op_like_votes"),
      numOrigPostWrongVotes = resultSet.getInt("num_op_wrong_votes"),
      numOrigPostBuryVotes = resultSet.getInt("num_op_bury_votes"),
      numOrigPostUnwantedVotes = resultSet.getInt("num_op_unwanted_votes"),
      numOrigPostRepliesVisible = resultSet.getInt("num_op_replies_visible"),
      answeredAt = getOptionalDate(resultSet, "answered_at"),
      answerPostUniqueId = getOptionalIntNoneNot0(resultSet, "answer_post_id"),
      plannedAt = getOptionalDate(resultSet, "planned_at"),
      doneAt = getOptionalDate(resultSet, "done_at"),
      closedAt = getOptionalDate(resultSet, "closed_at"),
      lockedAt = getOptionalDate(resultSet, "locked_at"),
      frozenAt = getOptionalDate(resultSet, "frozen_at"),
      htmlTagCssClasses = getStringOrEmpty(resultSet, "html_tag_css_classes"),
      numChildPages = resultSet.getInt("NUM_CHILD_PAGES"))
  }


  def _toTenantHostRole(roleStr: String) = roleStr match {
    case "C" => SiteHost.RoleCanonical
    case "R" => SiteHost.RoleRedirect
    case "L" => SiteHost.RoleLink
    case "D" => SiteHost.RoleDuplicate
  }


  def toDbMultireply(postNrs: Set[PostNr]) = {
    if (postNrs.isEmpty) NullVarchar
    else postNrs.mkString(",")
  }

  def fromDbMultireply(postNrsCommaSeparated: String): Set[PostNr] = {
    if (postNrsCommaSeparated == null) Set[PostNr]()
    else postNrsCommaSeparated.split(',').map(_.toInt).toSet
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


  def getCachedPageVersion(rs: js.ResultSet) = CachedPageVersion(
    siteVersion = rs.getInt("site_version"),
    pageVersion = rs.getInt("page_version"),
    appVersion = rs.getString("app_version"),
    dataHash = rs.getString("data_hash"))


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

