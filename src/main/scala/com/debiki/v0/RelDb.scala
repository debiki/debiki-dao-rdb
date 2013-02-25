/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 * Created on 5/31/11.
 */

package com.debiki.v0

import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js, lang => jl}
import javax.{sql => jxs}
import org.{postgresql => pg}


object RelDb {

  case class Null(sqlType: Int)
  val NullVarchar = Null(js.Types.VARCHAR)
  val NullTimestamp = Null(js.Types.TIMESTAMP)

  /**
   * Pimps `Option[String]` with `orNullVarchar`, which means
   * `getOrElse(Null(java.sql.Types.VARCHAR))`.
   * (There is already an `Option.orNull`.)
   */
  class StringOptionPimpedWithNullVarchar(opt: Option[String]) {
    def orNullVarchar = opt.getOrElse(NullVarchar)
  }
  implicit def pimpOptionWithNullVarchar(opt: Option[String]) =
    new StringOptionPimpedWithNullVarchar(opt)

  /** Converts null to the empty string ("Null To Empty"). */
  def n2e(s: String) = if (s eq null) "" else s

  /** Converts empty to SQL NULL. */
  def e2n(o: Option[String]) = o.getOrElse(Null(js.Types.VARCHAR))
    // Oracle: use NVARCHAR ?

  /** Converts empty to SQL NULL. */
  def e2n(s: String) = if (s != "") s else Null(js.Types.VARCHAR)

  /** Converts a dash to the empty string ("Dash To Empty"). */
  def d2e(s: String) = if (s == "-") "" else s

  /** Converts the empty string to a dash ("Empty To Dash"). */
  def e2d(s: String) = if (s isEmpty) "-" else s

  /** Converts dash and null to the empty string ("Dash or Null To Empty"). */
  def dn2e(s: String) = if ((s eq null) || s == "-") "" else s

  /** Converts java.util.Date to java.sql.Timestamp. */
  def d2ts(d: ju.Date) = new js.Timestamp(d.getTime)

  /** Converts an Option[Date] to Null or java.sql.Timestamp. */
  def o2ts(maybeDati: Option[ju.Date]) =
    maybeDati.map(d2ts(_)) getOrElse NullTimestamp

  /**
   * Converts java.sql.Timestamp to java.util.Date. (If you send a ju.Date
   * to the database, it throws away the fractional seconds value,
   * when saving and loading.)
   */
  def ts2d(ts: js.Timestamp) =
     (ts eq null) ? (null: ju.Date) | (new ju.Date(ts.getTime))

  def tOrNull(bool: Boolean) = if (bool) "T" else NullVarchar

  def isUniqueConstrViolation(sqlException: js.SQLException): Boolean = {
    // This status code means "A violation of the constraint imposed
    // by a unique index or a unique constraint occurred".
    sqlException.getSQLState == "23505"
  }

}


class RelDb(val server: String,
               val port: String,
               val database: String,
               val user: String,
               val password: String
){

  import RelDb._

  /*info("Connecting to PostgreSQL,"+  LOG
     "\n  server: "+ server +
     "\n  port:"+ port +
     "\n  database: "+ database +
     "\n  user: "+ user)  */

  jl.Class.forName("org.postgresql.Driver")

  val daSo: jxs.DataSource = {
    // (Oracle docs: -----------
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
    // -------- end Oracle docs)

    // Related docs:
    // PostgreSQL datasource:
    //  http://jdbc.postgresql.org/documentation/head/ds-ds.html
    //  http://jdbc.postgresql.org/documentation/head/ds-cpds.html
    // API:
    //  http://jdbc.postgresql.org/documentation/publicapi/org/
    //    postgresql/ds/PGPoolingDataSource.html

    // COULD read and implement:
    //   http://postgresql.1045698.n5.nabble.com/
    //      keeping-Connection-alive-td2172330.html
    //   http://www.rosam.se/doc/atsdoc/Server%20-%20Messages%20and%20Codes/
    //      ADC5906BBD514757BAEE546DC6F7A4FA/F183.htm
    //   http://stackoverflow.com/questions/1988570/
    //      how-to-catch-a-specific-exceptions-in-jdbc
    //   http://download.oracle.com/javase/6/docs/api/java/sql/SQLException.html

    // COULD use this nice pool instead:
    //   http://commons.apache.org/dbcp/configuration.html

    // Opening a datasource with the same name as a closed one fails with
    // an error that "DataSource has been closed.", in PostgreSQL.
    // PostgreSQL also doesn't allow one to open > 1 datasource with the
    // same name: "DataSource with name '<whatever>' already exists!"
    val ds = new pg.ds.PGPoolingDataSource()
    ds.setDataSourceName("DebikiPostgreConnCache"+ math.random)
    ds.setServerName(server)
    ds.setPortNumber(port.toInt)
    ds.setDatabaseName(database)
    ds.setUser(user)
    ds.setPassword(password)
    ds.setInitialConnections(2)
    ds.setMaxConnections(10)
    ds.setPrepareThreshold(3)
    //// The PrepareThreshold can also be specified in the connect URL, see:
    //// See http://jdbc.postgresql.org/documentation/head/server-prepare.html
    val pthr = ds.getPrepareThreshold

    //ds.setDefaultAutoCommit(false)
    //ds.setTcpKeepAlive()
    ////ds.setURL(connUrl)  // e.g. "jdbc:postgre//localhost:1521/database-name"
    //ds.setImplicitCachingEnabled(true)  // prepared statement caching
    //ds.setConnectionCachingEnabled(true)
    //ds.setConnectionCacheProperties(props)

    // Test the data source.
    val conn: js.Connection = try {
      ds.getConnection()
    } catch {
      case e: Exception => {
        error("Error connecting to `"+ server +"' port `"+ port +
            "' database `"+ database +"' as user `"+ user +"'")
        throw e
      }
    }
    conn.close()
    ds
  }


  def close() {
    // Results in PostgreSQL complaining that "DataSource has been closed",
    // also when you open another one (!) with a different name.
    //daSo.asInstanceOf[pg.ds.PGPoolingDataSource].close()
  }


  def transaction[T](f: (js.Connection) => T): T = {
    _withConnection(f, commit = true)
  }


  def withConnection[T](f: (js.Connection) => T): T = {
    _withConnection(f, commit = false)
  }


  private def _withConnection[T](f: (js.Connection) => T, commit: Boolean)
        : T = {
    var conn: js.Connection = null
    var committed = false
    try {
      conn = _getConnection()
      val result = f(conn)
      if (commit) {
        conn.commit()
        committed = true
      }
      result
    } catch {
      case e: Exception =>
        //warn("Error updating database [error DwE83ImQF]: "+  LOG
        //  classNameOf(e) +": "+ e.getMessage.trim)
        throw e
    } finally {
      _closeEtc(conn, rollback = commit && !committed)
    }
  }


  def query[T](sql: String, binds: List[AnyRef],
               resultSetHandler: js.ResultSet => T)
              (implicit conn: js.Connection): T = {
    execImpl(sql, binds, resultSetHandler, conn).asInstanceOf[T]
  }


  def queryAtnms[T](sql: String,
                    binds: List[AnyRef],
                    resultSetHandler: js.ResultSet => T): T = {
    execImpl(sql, binds, resultSetHandler, conn = null).asInstanceOf[T]
  }


  /**
   * Returns the number of lines updated, or throws an exception.
   */
  def update(sql: String, binds: List[AnyRef] = Nil)
            (implicit conn: js.Connection): Int = {
    execImpl(sql, binds, null, conn).asInstanceOf[Int]
  }


  def updateAny(sql: String, binds: List[Any] = Nil)
            (implicit conn: js.Connection): Int = {
    update(sql, binds.map(_.asInstanceOf[AnyRef]))(conn)
  }


  /**
   * Returns the number of lines updated, or throws an exception.
   */
  def updateAtnms(sql: String, binds: List[AnyRef] = Nil): Int = {
    execImpl(sql, binds, null, conn = null).asInstanceOf[Int]
  }


  /**
   * For calls to stored functions: """{? = call some_function(?, ?, ...) }"""
   */
  def call(sql: String, binds: List[AnyRef] = Nil, outParamSqlType: Int,
           resultHandler: (js.CallableStatement) => Unit)
          (implicit conn: js.Connection) {
    callImpl(sql, binds, outParamSqlType, resultHandler, conn)
  }


  private def execImpl(query: String, binds: List[AnyRef],
                resultSetHandler: js.ResultSet => Any,
                conn: js.Connection): Any = {
    val isAutonomous = conn eq null
    var conn2: js.Connection = null
    var pstmt: js.PreparedStatement = null
    var committed = false
    try {
      conn2 = if (conn ne null) conn else _getConnection()
      pstmt = conn2.prepareStatement(query)
      _bind(binds, pstmt)
      //s.setPoolable(false)  // don't cache infrequently used statements
      val result: Any = (if (resultSetHandler ne null) {
        val rs = pstmt.executeQuery()
        resultSetHandler(rs)
      } else {
        val updateCount = pstmt.executeUpdate()
        if (isAutonomous) {
          conn2.commit()
          committed = true
        }
        updateCount
      })
      COULD // handle errors, throw exception
      result
    } catch {
      case ex: js.SQLException =>
        //warn("Database error [error DwE83ikrK9]: "+ ex.getMessage.trim) LOG
        //warn("{}: {}", errmsg, ex.printStackTrace)
       throw ex
    } finally {
      if (pstmt ne null) pstmt.close()
      if (isAutonomous) _closeEtc(conn2, rollback = !committed)
    }
  }


  def batchUpdateAny(
        stmt: String, batchValues: List[List[Any]], batchSize: Int = 100)
        (implicit conn: js.Connection): Seq[Array[Int]] = {
    batchUpdate(stmt, batchValues.map(_.map(_.asInstanceOf[AnyRef])), batchSize)
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
    } catch {
      // A batch update seems to generate chained exceptions. Replace them
      // with one single exception that includes info on all errors
      // (not just the first one).
      case terseEx: java.sql.SQLException =>
        val sb = new StringBuilder()
        var nextEx = terseEx
        do {
          if (terseEx ne nextEx) sb ++= "\nCalled getNextException:\n"
          sb ++= nextEx.toString()
          nextEx = nextEx.getNextException()
        } while (nextEx ne null)
        val verboseEx = new js.SQLException(sb.toString,
              terseEx.getSQLState(), terseEx.getErrorCode())
        verboseEx.setStackTrace(terseEx.getStackTrace)
        throw verboseEx
    } finally {
      if (pstmt ne null) pstmt.close()
      if (isAutonomous) _closeEtc(conn2, rollback = !committed)
    }
  }


  private def callImpl[A](query: String, binds: List[AnyRef],
        outParamSqlType: Int, resultHandler: (js.CallableStatement) => Unit,
        conn: js.Connection) {
    // (Optionally, see my self-answered StackOverflow question:
    //  http://stackoverflow.com/a/15063409/694469 )
    var statement: js.CallableStatement = null
    try {
      statement = conn.prepareCall(query)
      statement.registerOutParameter(1, outParamSqlType)
      _bind(binds, statement, firstBindPos = 2)
      statement.execute()
      resultHandler(statement)
    }
    finally {
     if (statement ne null) statement.close()
    }
  }


  private def _bind(
        values: List[AnyRef], pstmt: js.PreparedStatement, firstBindPos: Int = 1) {
    var bindPos = firstBindPos
    for (v <- values) {
      v match {
        case i: jl.Integer => pstmt.setInt(bindPos, i.intValue)
        case l: jl.Long => pstmt.setLong(bindPos, l.longValue)
        case s: String => pstmt.setString(bindPos, s)
        case d: js.Date => assErr("DwE0kiesE4", "Use Timestamp not Date")
        case t: js.Time => assErr("DwE96SK3X8", "Use Timestamp not Time")
        case t: js.Timestamp => pstmt.setTimestamp(bindPos, t)
        case d: ju.Date => pstmt.setTimestamp(bindPos, d2ts(d))
        case Null(sqlType) => pstmt.setNull(bindPos, sqlType)
        case x => unimplemented("Binding this: "+ classNameOf(x))
      }
      bindPos += 1
    }
  }

  private def _getConnection(): js.Connection = {
    val conn: js.Connection = daSo.getConnection()
    if (conn ne null) conn.setAutoCommit(false)
    conn
  }

  private def _closeEtc(conn: js.Connection, rollback: Boolean) {
    // Need to rollback before closing? Read:
    // http://download.oracle.com/javase/6/docs/api/java/sql/Connection.html:
    // "It is strongly recommended that an application explicitly commits
    // or rolls back an active transaction prior to calling the close method.
    // If the close method is called and there is an active transaction,
    // the results are implementation-defined."
    if (conn eq null) return
    if (rollback) conn.rollback()
    conn.setAutoCommit(true)  // reset to default mode
    conn.close()
  }


  def nextSeqNoAnyRef(seqName: String)(implicit conn: js.Connection): AnyRef =
    nextSeqNo(seqName).asInstanceOf[AnyRef]


  def nextSeqNo(seqName: String)(implicit conn: js.Connection): Long = {
    val sno: Long = query("select nextval('"+ seqName +"') N",
          Nil, rs => {
      rs.next
      rs.getLong("N")
    })
    sno
  }
}


