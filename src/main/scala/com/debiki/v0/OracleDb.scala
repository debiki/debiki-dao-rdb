/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 * Created on 5/31/11.
 */

package com.debiki.v0

import _root_.net.liftweb._
import common._
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import oracle.{jdbc => oj}
import oracle.jdbc.{pool => op}

class OracleDb(val connUrl: String, val user: String, pswd: String)
extends Logger {

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
    val conn: js.Connection = ds.getConnection()
    conn.close()
    ds
  }

  def close() {
    daSo.close()
  }

  def transaction(f: (js.Connection) => Unit) = {
    val conn: js.Connection = daSo.getConnection()
    try {
      conn.setAutoCommit(false)
      f(conn)
      conn.commit()
      conn.setAutoCommit(true)  // reset to default mode
    } catch {
      case e =>
        conn.close()
        throw e
    }
  }

  def execQuery[T](query: String,
                    binds: List[AnyRef],
                    resultSetHandler: js.ResultSet => Box[T]): Box[T] = {
    execImpl(query, binds, resultSetHandler, null)
  }

  def execUpdate[T](query: String,
                     binds: List[AnyRef] = Nil,
                     updateCountHandler: Int => Box[T] = null): Box[T] = {
    val handler =
      if (updateCountHandler ne null) updateCountHandler
      else { count: Int => Full(count) }
    execImpl(query, binds, null, updateCountHandler)
  }

  def execImpl[T](query: String, binds: List[AnyRef],
                resultSetHandler: js.ResultSet => Box[T],
                numUpdatesHandler: Int => Box[T]): Box[T] = {
    try {
      val conn: js.Connection = daSo.getConnection()
      val pstmt: js.PreparedStatement = conn.prepareStatement(query)
      TODO // handle binds
      //s.setPoolable(false)  // don't cache infrequently used statements
      val result = (if (resultSetHandler ne null) {
        val rs = pstmt.executeQuery()
        resultSetHandler(rs)
      } else {
        val updateCount = pstmt.executeUpdate()
        numUpdatesHandler(updateCount)
      })
      TODO // handle errors, return a failure
      pstmt.close()
      conn.close()
      result
    } catch {
      case ex: js.SQLException =>
        val errmsg = "Database error [debiki_error_83ikrK92]"
        warn(errmsg +": ", ex)
        //warn("{}: {}", errmsg, ex.printStackTrace)
        Failure(errmsg, Full(ex), Empty)
    }
  }

  /*
  def execQueryOLD(query: String, binds: List[AnyRef],
                        resultHandler: (Either[Exception, js.ResultSet])
                            => Unit) = {
    val conn: js.Connection = daSo.getConnection()
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
    val conn: js.Connection = daSo.getConnection()
    val pstmt: js.PreparedStatement = conn.prepareStatement(dml)
    TODO // handle binds
    val changedRowsCount = pstmt.executeUpdate()
    resultHandler(Right(changedRowsCount))
    pstmt.close()
    conn.close()
  }
  */

}


