/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 * Created on 5/31/11.
 */

package com.debiki.v0

import _root_.net.liftweb.common.{Logger, Box, Empty, Full, Failure}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js, lang => jl}

trait OraLogger extends Logger {
  def logAndFailure[T](errorMessage: String, ex: Exception): Box[T] = {
    warn(errorMessage +": ", ex)
    Failure(errorMessage, Full(ex), Empty)
  }
}

// Could: UpdateThrow / UpdateBox / UpdateBoxErrOk / UpdateThrowErrOk

object OracleDb {
  case class Null(sqlType: Int)

  /** Converts null to the empty string ("Null To Empty"). */
  def n2e(s: String) = if (s eq null) "" else s

  /** Converts space to the empty string ("Space To Empty"). */
  def s2e(s: String) = if (s == " ") "" else s

  /** Converts the empty string to space ("Empty To Space"). */
  def e2s(s: String) = if (s isEmpty) " " else s

  /** Converts a dash to the empty string ("Dash To Empty"). */
  def d2e(s: String) = if (s == "-") "" else s

  /** Converts the empty string to a dash ("Empty To Dash"). */
  def e2d(s: String) = if (s isEmpty) "-" else s

  /** Converts java.util.Date to java.sql.Timestamp. */
  def d2ts(d: ju.Date) = new js.Timestamp(d.getTime)

  /** Converts java.sql.Timestamp to java.util.Date. */
  def ts2d(ts: js.Timestamp) = new ju.Date(ts.getTime)
}

class OracleDb(val connUrl: String, val user: String, pswd: String)
extends OraLogger {

  import OracleDb._

  /*
  val daSo = {
    // COULD disable deprecations. (You'll notice some warnings, e.g.
    // "setConnectionCachingEnabled in class OracleDataSource is deprecated"
    // when you compile.)
    // From <http://forums.oracle.com/forums/
    //        thread.jspa?threadID=1028439&tstart=5>:
    // "The Implicit Connection Cache is still supported in 11.2.
    // All of the features it provides, and more, are available in the UCP.
    // We encourage you to use UCP for all new development and transition
    // existing code to UCP as possible. While we don't intend to desupport
    // the Implicit Connection Cache for sometime, we will be rewriting it
    // so that it is just a wrapper around the UCP."

    // Related documentation:
    // Statement caching: <http://download.oracle.com/docs/cd/E11882_01/
    //                              java.112/e16548/stmtcach.htm#i1072607>

    // Oracle JDBC API docs:
    // <http://download.oracle.com/docs/cd/E11882_01/appdev.112/
    //            e13995/index.html?oracle/jdbc/pool/OracleDataSource.html>

    // Implicit statement & connection cache examples:
    // <http://download.oracle.com/docs/cd/E11882_01/java.112/e16548/
    //                                                stmtcach.htm#CBHBFBAF>
    // <http://download.oracle.com/docs/cd/E11882_01/java.112/e16548/
    //                                                concache.htm#CDEDAIGA>

    // Article: "High-Performance Oracle JDBC Programming"
    // e.g. info on statement caching:
    //  <javacolors.blogspot.com/2010/12/high-performance-oracle-jdbc.html>

    // Batch insert example: (ignores prepared statements though)
    //  <http://www.roseindia.net/jdbc/Jdbc-batch-insert.shtml>

    // set cache properties
    val props = new ju.Properties()
    props.setProperty("InitialLimit", "1") // create 1 connection at startup
    props.setProperty("MinLimit", "1")
    props.setProperty("MaxLimit", "10")
    props.setProperty("MaxStatementsLimit", "50")
    val ds = new op.OracleDataSource()
    ds.setURL(connUrl)  // e.g. "jdbc:oracle:thin@localhost:1521:UORCL"
    ds.setUser(user)
    ds.setPassword(pswd)
    ds.setImplicitCachingEnabled(true)  // prepared statement caching
    ds.setConnectionCachingEnabled(true)
    ds.setConnectionCacheProperties(props)
    ds.setConnectionCacheName("DebikiConnCache01")
    // Test the data source.
    val conn: js.Connection = try {
      ds.getConnection()
    } catch {
      case e: Exception => {
        error("Error connecting to `"+ connUrl +"' as `"+ user +"'")
        throw e
      }
    }
    conn.close()
    ds
  }
  */

  def close() {
    unimplemented //daSo.close()
  }

  /*
  def transactionThrow(f: (js.Connection) => Unit) = {
    val conn: js.Connection = _getConnection()
    try {
      f(conn)
      conn.commit()
    } catch {
      case e =>
        ! use _closeEtc instead
        if (!committed) conn.rollback()
        conn.close()
        throw e
    }
  } */

  def transaction[T](f: (js.Connection) => Box[T]): Box[T] = {
    var conn: js.Connection = null
    var box: Box[T] = Empty
    var committed = false
    try {
      conn = _getConnection()
      box = f(conn)
      conn.commit()
      committed = true
    } catch {
      case e: Exception =>
        //box = logAndFailure("Error updating database [debiki_error_83ImQF]", e)
        warn("Error updating database [debiki_error_83ImQF]: "+
            classNameOf(e) +": "+ e.getMessage.trim)
        throw e
    } finally {
      _closeEtc(conn, committed = committed)
    }
    box
  }

  //def transactionThrow[T](f: (js.Connection) => Box[T]): Box[T] = {
  //  var conn: js.Connection = null
  //  var box: Box[T] = Empty
  //  try {
  //    conn = _getConnection()
  //    box = f(conn)
  //    conn.commit()
  //  } finally {
  //    if (conn ne null) {
  //      ! use _closeEtc instead
  //      conn.setAutoCommit(true)  // reset to default mode
  //      conn.close()
  //    }
  //  }
  //  box
  //}

  def query[T](sql: String, binds: List[AnyRef],
               resultSetHandler: js.ResultSet => Box[T])
              (implicit conn: js.Connection): Box[T] = {
    execImpl(sql, binds, resultSetHandler, conn).asInstanceOf[Box[T]]
  }

  def queryAtnms[T](sql: String,
                    binds: List[AnyRef],
                    resultSetHandler: js.ResultSet => Box[T]): Box[T] = {
    execImpl(sql, binds, resultSetHandler, conn = null).asInstanceOf[Box[T]]
  }

  /** Returns Full(num-lines-updated) or Failure.
    */
  def update(sql: String, binds: List[AnyRef] = Nil)
            (implicit conn: js.Connection): Box[Int] = {
    execImpl(sql, binds, null, conn).asInstanceOf[Box[Int]]
  }

  def updateAtnms(sql: String, binds: List[AnyRef] = Nil): Box[Int] = {
    execImpl(sql, binds, null, conn = null).asInstanceOf[Box[Int]]
  }

  private def execImpl(query: String, binds: List[AnyRef],
                resultSetHandler: js.ResultSet => Box[Any],
                conn: js.Connection): Box[Any] = {
    val isAutonomous = conn eq null
    var conn2: js.Connection = null
    var pstmt: js.PreparedStatement = null
    var committed = false
    try {
      conn2 = if (conn ne null) conn else _getConnection()
      pstmt = conn2.prepareStatement(query)
      _bind(binds, pstmt)
      //s.setPoolable(false)  // don't cache infrequently used statements
      val result = (if (resultSetHandler ne null) {
        val rs = pstmt.executeQuery()
        resultSetHandler(rs)
      } else {
        val updateCount = pstmt.executeUpdate()
        val result = Box !! updateCount
        if (isAutonomous) {
          conn2.commit()
          committed = true
        }
        result
      })
      TODO // handle errors, return a failure
      result
    } catch {
      case ex: js.SQLException =>
        warn("Database error [debiki_error_83ikrK9]: "+ ex.getMessage.trim)
        //warn("{}: {}", errmsg, ex.printStackTrace)
        //Failure(errmsg, Full(ex), Empty)
       throw ex
    } finally {
      if (pstmt ne null) pstmt.close()
      if (isAutonomous) _closeEtc(conn2, committed = committed)
    }
  }

  def batchUpdate(
         stmt: String, batchValues: List[List[AnyRef]], batchSize: Int = 100)
         (implicit conn: js.Connection): Seq[Array[Int]] = {
    assert(batchSize > 0)
    val isAutonomous = conn eq null
    var conn2: js.Connection = null
    var pstmt: js.PreparedStatement = null
    var result = List[Array[Int]]()
    var committed = false
    try {
      conn2 = if (conn ne null) conn else _getConnection()
      pstmt = conn2.prepareStatement(stmt)
      var rowCount = 0
      for (values <- batchValues) {
        rowCount += 1
        _bind(values, pstmt)
        pstmt.addBatch()
        if (rowCount == batchSize) {
          val updateCounts = pstmt.executeBatch() ; UNTESTED
          result ::= updateCounts
          rowCount = 0
        }
      }
      if (rowCount > 0) {
        val updateCounts = pstmt.executeBatch()
        result ::= updateCounts
      }
      if (isAutonomous) {
        conn2.commit()
        committed = true
      }
      result.reverse
    } finally {
      if (pstmt ne null) pstmt.close()
      if (isAutonomous) _closeEtc(conn2, committed = committed)
    }
  }

  private def _bind(values: List[AnyRef], pstmt: js.PreparedStatement) {
    var bindPos = 0
    for (v <- values) {
      bindPos += 1
      v match {
        case i: jl.Integer => pstmt.setInt(bindPos, i.intValue)
        case l: jl.Long => pstmt.setLong(bindPos, l.longValue)
        case s: String => pstmt.setString(bindPos, s)
        case d: js.Date => assErr("Use Timestamp not Date")
        case t: js.Time => assErr("Use Timestamp not Time")
        case t: js.Timestamp => pstmt.setTimestamp(bindPos, t)
        case d: ju.Date => pstmt.setTimestamp(bindPos, d2ts(d))
        case Null(sqlType) => pstmt.setNull(bindPos, sqlType)
        case x => unimplemented("Binding this: "+ classNameOf(x))
      }
    }
  }

  private def _getConnection(): js.Connection = {
    val conn: js.Connection = unimplemented // daSo.getConnection()
    if (conn ne null) conn.setAutoCommit(false)
    conn
  }

  private def _closeEtc(conn: js.Connection, committed: Boolean) {
    // Need to rollback before closing? Read:
    // http://download.oracle.com/javase/6/docs/api/java/sql/Connection.html:
    // "It is strongly recommended that an application explicitly commits
    // or rolls back an active transaction prior to calling the close method.
    // If the close method is called and there is an active transaction,
    // the results are implementation-defined."
    if (conn eq null) return
    if (!committed) conn.rollback()
    conn.setAutoCommit(true)  // reset to default mode
    conn.close()
  }

  /*
  def execQueryOLD(query: String, binds: List[AnyRef],
                        resultHandler: (Either[Exception, js.ResultSet])
                            => Unit) = {
    val conn: js.Connection = _getConnection()
    val pstmt: js.PreparedStatement = conn.prepareStatement(query)
    TODO // handle binds
    //s.setPoolable(false)  // don't cache infrequently used statements
    val resultSet = pstmt.executeQuery()
    resultHandler(Right(resultSet))
    pstmt.close()
    conn.close()
  }

  def execUpdateX(dml: String, binds: List[AnyRef],
                         resultHandler: (Either[Exception, Int]) => Unit) = {
    val conn: js.Connection = _getConnection()
    val pstmt: js.PreparedStatement = conn.prepareStatement(dml)
    TODO // handle binds
    val changedRowsCount = pstmt.executeUpdate()
    resultHandler(Right(changedRowsCount))
    pstmt.close()
    conn.close()
  }
  */

  def nextSeqNoAnyRef(seqName: String)(implicit conn: js.Connection): AnyRef =
    nextSeqNo(seqName).asInstanceOf[AnyRef]

  def nextSeqNo(seqName: String)(implicit conn: js.Connection): Long = {
    val sno: Long = query("select "+ seqName +".nextval N from dual",
          Nil, rs => {
      rs.next
      Full(rs.getLong("N"))
    }).open_!
    sno
  }
}


