/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

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

  def create(tenantId: String, debatePerhapsGuid: Debate): Box[Debate] = {
    var debate = if (debatePerhapsGuid.guid != "?") {
      debatePerhapsGuid
    } else {
      debatePerhapsGuid.copy(guid = nextRandomString)  // TODO use the same
                                              // method in all DAO modules!
    }
    db.transaction { implicit connection =>
      _createPage(tenantId, debate)
      _insert(tenantId, debate.guidd, debate.posts)
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

  def findPageInfo(tenantId: String, parentFolder: String,
                   pageGuid: String, pageName: String, userId: String
                   ): Box[PageInfo] = {
    /*
    The algorithm:

    if guid:
      lookup path in PAGEPATHS
    else
      lookup guid in PAGEPATHS (given parentFolder & pageName)

    lookup PageRules in PATHRULES:  (not implemented! paths hardcoded instead)
      if guid, try:  parentFolder / -* /   (i.e. any guid in folder)
      else, try:
        first: parentFolder / pageName /   (this particular page)
        then:  parentFolder / * /          (any page in folder)
      Then continue with the parent folders:
        first: parentsParent / parentFolderName /
        then: parentsParent / * /
      and so on with the parent's parent ...
     */

    val (path, guid) = {
      if (pageGuid nonEmpty) {
        _lookupPathByGuid(tenantId, pageGuid) match {
          case Full(p) =>
            // If the user agent is'n aware of the actual location of
            // the page, return a BadPath so the caller can issue a redirect
            if (p != (parentFolder +"-"+ pageGuid +"-"+ pageName +"/") &&
                p != (parentFolder + pageName +"/")) {
              // COULD change the table layout so the guid in the path
              // cannot possibly differ from the page's guid.
              // Right now, if the guid in the path doesn't match the actual
              // guid, a redirection loop might follow?!
              // See a "COULD" in OracleSchema.scala.
              return Full(PageInfo.BadPath(p))
            }
            (p, pageGuid)
          case Empty => return Empty
          case f: Failure => return f.asA[PageInfo]
        }
      }
      else {
        // COULD extract make-path function, place in debiki-core?
        val p = parentFolder + pageName +"/"
        _lookupGuidByPath(tenantId, p) match {
          case Full(g) => (p, g)
          case Empty => return Empty
          case f: Failure => return f.asA[PageInfo]
        }
      }
    }

    // Now the path and guid are known and correct.
    // Should look up rules, for `path' and `userId', in PATHRULES
    // (doesn't exist), but for now, hardcode /wiki/, /forum/, /test/ etc
    // paths, for all users:
    import PageRules._
    val rules: PageRules = {
      if (parentFolder startsWith "/test/") AllOk
      else if (parentFolder startsWith "/wiki/") AllOk
      else if (parentFolder startsWith "/forum/") AllOk
      else HiddenTalk
    }
    return Full(PageInfo.Info(guid = guid, path = path, rules = rules))
  }

  private def _lookupGuidByPath(tenantId: String,
                                path: String): Box[String] = {
    db.queryAtnms("""
        select PAGE from DW0_PATHS
        where TENANT = ? and PATH = ?
        """,
        tenantId::path::Nil, rs => {
      var guid: Box[String] = Empty
      if (rs.next) {
        guid = Full(rs.getString("PAGE"))
      }
      assert(!rs.next)  // selected by primary key
      guid
    })
  }

  private def _lookupPathByGuid(tenantId: String,
                                guid: String): Box[String] = {
    db.queryAtnms("""
        select PATH from DW0_PATHS
        where TENANT = ? and PAGE = ?
        """,
        tenantId::guid::Nil,
        rs => {
      var path: Box[String] = Empty
      if (rs.next) {
        path = Full(rs.getString("PATH"))
      }
      assert(!rs.next)  // selected by unique key
      path
    })
  }

  private def _createPage[T](tenantId: String, debate: Debate)
                            (implicit conn: js.Connection): Box[Int] = {
    db.update("""
        insert into DW0_PAGES (TENANT, GUID) values (?, ?)
        """,
        List(tenantId, debate.guid))
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
