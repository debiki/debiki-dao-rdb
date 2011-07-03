/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.IntrsAllowed._
import com.debiki.v0.{Action => A}
import _root_.net.liftweb.common.{Logger, Box, Empty, Full, Failure}
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import oracle.{jdbc => oj}
import oracle.jdbc.{pool => op}

object OracleDao {

  /** Returns a failure or a Full(dao). */
  def connectAndUpgradeSchema(
      connUrl: String, user: String, pswd: String): Box[OracleDao] = {
    try {
      Full(connectAndUpgradeSchemaThrow(connUrl, user, pswd))
    } catch {
      case ex: Exception => Failure(
        "Error creating DAO [debiki_error_8skeFQ2]", Full(ex), Empty)
    }
  }

  /** Returns a working DAO or throws an error. */
  def connectAndUpgradeSchemaThrow(
      connUrl: String, user: String, pswd: String): OracleDao = {
    // COULD catch connection errors, and return Failure.
    val oradb = new OracleDb(connUrl, user, pswd)  // can throw SQLException
    val schema = new OracleSchema(oradb)
    val ups: Box[List[String]] = schema.upgrade()
    val curVer = OracleSchema.CurVersion
    ups match {
      case Full(`curVer`::_) => new OracleDao(schema)
      case Full(_) => assErr("[debiki_error_77Kce29h]")
      case Empty => assErr("[debiki_error_33k2kSE]")
      case f: Failure => throw f.exception.open_!
    }
  }
}


class OracleDao(val schema: OracleSchema) extends Dao {
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
      _insert(where.tenantId, debate.guid, debate.posts)
      Full(debate)
    }
  }

  def save(tenantId: String, pageGuid: String, x: AnyRef): Box[AnyRef] = {
    save(tenantId, pageGuid, x::Nil).map(_.head)
  }

  def save[T](tenantId: String, pageGuid: String,
                xs: List[T]): Box[List[T]] = {
    db.transaction { implicit connection =>
      _insert(tenantId, pageGuid, xs)
    }
  }

  def load(tenantId: String, pageGuid: String): Box[Debate] = {
    // Consider using TRANSACTION_REPEATABLE_READ? Not needed right now.
    // Order by TIME desc, because when the action list is constructed
    // the order is reversed again.
    db.queryAtnms("""
        select ID, TYPE, TIME, WHO, IP, RELA, DATA, DATA2
        from DW0_ACTIONS
        where TENANT = ? and PAGE = ?
        order by TIME desc
        """,
        List(tenantId, pageGuid), rs => {
      var actions = List[AnyRef]()

      while (rs.next) {
        val id = rs.getString("ID");
        val typee = rs.getString("TYPE");
        val at = ts2d(rs.getTimestamp("TIME"))
        val by = rs.getString("WHO")
        val ip = rs.getString("IP")
        val rela_? = rs.getString("RELA")  // can be null
        val data_? = rs.getString("DATA")
        val data2_? = rs.getString("DATA2")

        val action = typee match {
          case "Post" =>
            new Post(id = id, parent = n2e(rela_?), date = at,
              by = by, ip = ip, text = n2e(data_?),
              where = Option(data2_?))
          case "Edit" =>
            // COULD assert rela_? is not null.
            new Edit(id = id, postId = n2e(rela_?), date = at,
              by = by, ip = ip, text = n2e(data_?),
              desc = n2e(data2_?))
          case "EdAp" =>
            // COULD assert rela_? is not null.
            new EditApplied(editId = n2e(rela_?), date = at,
              result = n2e(data_?), debug = n2e(data2_?))
          case x => return Failure(
              "Bad DW0_ACTIONS.TYPE: "+ safed(typee) +" [debiki_error_Y8k3B]")
        }
        actions ::= action  // this reverses above `order by TIME desc'
      }

      Full(Debate.fromActions(guid = pageGuid, actions))
    })
  }

  def checkPagePath(pathToCheck: PagePath): Box[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def checkAccess(pagePath: PagePath, userId: String, action: Action
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
      else if (action == A.Create)
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
        select PARENT, PAGE_GUID, PAGE_NAME, GUID_IN_PATH
        from DW0_PAGEPATHS
        where TENANT = ?
        """
    var binds = List(pagePathIn.tenantId)
    var maxRowsFound = 1  // there's a unique key
    pagePathIn.guid.value match {
      case Some(guid) =>
        query += " and PAGE_GUID = ?"
        binds ::= guid
      case None =>
        // GUID_IN_PATH = ' ' means that the page guid must not be part
        // of the page url. ((So you cannot look up [a page that has its guid
        // as part of its url] by searching for its url without including
        // the guid. Had that been possible, many pages could have been found
        // since pages with different guids can have the same name.
        // Hmm, could search for all pages, as if the guid hadn't been
        // part of their name, and list all pages with matching names?))
        query += """
            and GUID_IN_PATH = ' '
            and (
              (PARENT = ? and PAGE_NAME = ?)
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
              or (PARENT = ? and PAGE_NAME = ' ')
              )
            order by length(PARENT)
            """
          binds ::= pagePathIn.parent + pagePathIn.name +"/"
          maxRowsFound = 2
        }
        else if (pagePathIn.parent.count(_ == '/') >= 2) {
          // Perhaps the correct path is /folder/page not /folder/page/.
          // But prefer /folder/page/ if both pages are found.
          query += """
              or (PARENT = ? and PAGE_NAME = ?)
              )
            order by length(PARENT) desc
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
            if (s2e(rs.getString("GUID_IN_PATH")) == "") GuidHidden(guidVal)
            else GuidInPath(guidVal)
        correctPath = Full(pagePathIn.copy(  // keep tenantId
            parent = rs.getString("PARENT"),
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
        insert into DW0_PAGES (TENANT, GUID) values (?, ?)
        """,
        List(where.tenantId, debate.guid))

    db.update("""
        insert into DW0_PAGEPATHS (TENANT, PARENT, PAGE_GUID,
                                   PAGE_NAME, GUID_IN_PATH)
        values (?, ?, ?, ?, ?)
        """,
        List(where.tenantId, where.parent, debate.guid, e2s(where.name),
          debate.guid  // means the guid will prefix the page name
        ))
  }

  private def _insert[T](tenantId: String, pageGuid: String, xs: List[T])
                        (implicit conn: js.Connection): Box[List[T]] = {
    var xsWithIds = (new Debate("dummy")).assignIdTo(
                      xs.asInstanceOf[List[AnyRef]]).asInstanceOf[List[T]]
                          // COULD make `assignIdTo' member of object Debiki$
    var bindPos = 0
    for (x <- xsWithIds) {
      // TODO don't insert sysdate, but the same timestamp for all entries!
      val insertIntoActions = """
          insert into DW0_ACTIONS(TENANT, PAGE, ID, TYPE,
              TIME, WHO, IP, RELA, DATA, DATA2)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """
      x match {
        case p: Post =>
          db.update(insertIntoActions,
            List(tenantId, pageGuid, p.id, "Post", p.date, p.by,
                p.ip, p.parent, p.text,
                p.where.getOrElse(Null(js.Types.NVARCHAR))))
        case e: Edit =>
          db.update(insertIntoActions,
            List(tenantId, pageGuid, e.id, "Edit", e.date, e.by,
                  e.ip, e.postId, e.text, e.desc))
        case a: EditApplied =>
          val id = nextRandomString()  ; TODO // guid field
          db.update(insertIntoActions,
            List(tenantId, pageGuid, id, "EdAp", a.date, "<system>",
              "?.?.?.?", a.editId, a.result, ""))
        case x => unimplemented(
          "Saving this: "+ classNameOf(x) +" [debiki_error_38rkRF]")
      }
    }
    Full(xsWithIds)
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
