/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import _root_.net.liftweb._
import common._
import actor._
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


class OracleDao(val schema: OracleSchema) extends Dao with Logger {
  // COULD serialize access, per page?

  def db = schema.oradb

  def close() { db.close() }

  def checkRepoVersion() = schema.readCurVersion()

  def create(tenantId: String, debatePerhapsId: Debate): Box[Debate] = {
    var debateWithId = if (debatePerhapsId.id != "?") {
      debatePerhapsId
    } else {
      debatePerhapsId.copy(id = nextRandomString)  // TODO use the same
                                              // method in all DAO modules!
    }
    // _impl.createPage(tenantId, debate.id)
    try { db.transaction { conn =>
      //db.execUpdate("insert into DW000_ACTIONS ...")

    }} catch {
      case e: Exception => Failure(
        "Database error [debiki_error_3rtsk7kS]", Full(e), Empty)
    }
    unimplemented
  }

  def save(tenantId: String, debateId: String, x: AnyRef): Box[AnyRef] = {
    unimplemented // delete this overload
  }

  def save[T](tenantId: String, debateId: String,
                xs: List[T]): Box[List[T]] = {
    db.execUpdate("update dual set muu = 'mää'")

//      var failure: Box[List[T]] = Empty
//      for (x <- xs) yield x match {
//        case g: Debate => _impl.savePage(tenantId, p)
//        case p: Post => _impl.savePost(tenantId, p)
//        case e: Edit => _impl.saveEdit(tenantId, e)
//        case x: failure = Failure(
//      "Cannot save a `"+ classNameFor(x) +"' [debiki_error_983hUzq5J]")
//    }
    unimplemented
  }

  def load(tenantId: String, debateId: String): Box[Debate] = {
    //db.execQuery("select 1 from dual", Nil, _ match {
    //  case Right(resultSet) =>
    //  case Left(exception) =>
    //})
    Failure("Not implemented [debiki_error_335k23355s3]")
  }

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
