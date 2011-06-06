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

/* Create a user like so: (the tablespace and user name can be anything)

CREATE USER "DEBIKI_TEST"
  PROFILE "DEFAULT"
  IDENTIFIED BY "*******"
  DEFAULT TABLESPACE "DEBIKI"
  TEMPORARY TABLESPACE "TEMP"
  QUOTA UNLIMITED ON "DEBIKI"
  ACCOUNT UNLOCK;
GRANT CREATE PROCEDURE TO "DEBIKI_TEST";
GRANT CREATE SEQUENCE TO "DEBIKI_TEST";
GRANT CREATE TABLE TO "DEBIKI_TEST";
GRANT CREATE TRIGGER TO "DEBIKI_TEST";
GRANT CREATE TYPE TO "DEBIKI_TEST";
GRANT CREATE VIEW TO "DEBIKI_TEST";
GRANT "CONNECT" TO "DEBIKI_TEST";

Tables, version 0.0.2:

For now, only one huge DW_ACTIONS table, and a DW_USERS table:
(just to get started quickly)

"DW002" means DebateWiki (Debiki) version 0.0.2
When upgrading, one can copy data to new tables, e.g. DW003_..., instead of
modifying data in the current tables. Then it's almost impossible to
corrupt any existing data.
  --- and if you don't need to upgrade, then keep the DW002 tables,
  (and perhaps let new DW00X tables reference DW002 tables).

DW002_ACTIONS  Description
  PAGE_GUID
  ID           The id of this action is unique within the page only.
  TYPE         Post / Edit / EditApp / Rating.
  DATE
  BY
  IP
  TO_ACTION    reply/rate-which-post / edit-of-which-post / apply-which-edit
  DATA         post-text / edit-diff / edit-app-result / rating-tags
  DATA2        post-where / edit-description / edit-app-debug / empty

DW002_USERS
  PK           locally generated sequence number used as primary key
  NAME
  EMAIL
  URL

DW_VERSION
  VERSION      one single row: "0.0.2"


In the future, ought to normalize, to save storage space and allow for
faster gathering of statistics:

Classes summary:

post           edit           edit_app          rating
  page-guid      page-guid      page-guid         page-guid
  id             id
  date           date           date              date
  by             by             by (user/sys)     by
  ip             ip             ip (n/a if sys)   ip
  text           text/diff      result
  parent-post    post-id        edit-id
  where-in-post
                 description
                                debug
                                                  list-of-strings

Appropriate tables?

(Perhaps change from DW0 to DW1 ?)

DW0_PAGE
  PK         locally (this db) unique identifier, taken from sequence
  GUID       globally unique identifier

DW0_USERS
  PK
  NAME
  EMAIL
  URL

DW0_ACTIONS
  PK
  PAGE        foreign key to DW_PAGE.PK
  ID
  DATE
  BY
  IP

DW0_POSTS
  ACTION      foreign key to DW_ACTIONS.PK
  PARENT      foreign key to DW_POSTS.ACTION
  WHERE
  TEXT

DW0_EDITS
  ACTION
  POST
  TEXT
  DESC

DW0_EDIT_APPS
  ACTION
  EDIT
  RESULT
  DEBUG

DW0_RATINGS
  ACTION
  TAGVAL

DW0_VALS
  PK
  VALUE


 */

object OracleSchema {
  val CurVersion = "0.0.2"
}

/** Upgrades the database schema to the latest version.
 */
class OracleSchema(val oradb: OracleDb) {

  import OracleSchema._

  private def exUp(sql: String) = oradb.execUpdate(sql, Nil, Full(_))
  private def exQy = oradb.execQuery _

  /* All upgrades done, by this server instance, to convert the
   * database schema to the most recent version.
   */
  private var _upgrades: List[String] = Nil
  def upgrades = _upgrades

  def readCurVersion(): Box[String] = {
    for {
      ok <- oradb.execQuery("""
          select count(*) c from USER_TABLES
          where TABLE_NAME = 'DW_VERSION'
          """,  Nil, rs => {
        val v = { rs.next; rs.getString("c") }
        if (v == "0") return Full("0")  // version 0 means an empty schema
        if (v == "1") Full("1")
        else Failure("[debiki_error_6rshk23r]")
      })
      v <- oradb.execQuery("""
          select VERSION v from DW_VERSION
          """,  Nil, rs => {
        if (!rs.next) {
          Failure("Empty version table [debiki_error_7sh3RK]")
        } else {
          val v = Full(rs.getString("v"))
          if (rs.next) {
            Failure("Many rows in version table [debiki_error_25kySR2]")
          } else {
            v
          }
        }
      })
    }
    yield v
  }

  /** Upgrades to the most recent structure version. Returns Full(upgrades)
   *  or a Failure.
   */
  def upgrade(): Box[List[String]] = {
    var vs = List[String]()
    var i = 9
    while ({ i -= 1; i > 0 }) {
      val v: Box[String] = readCurVersion()
      if (!v.isDefined) return v.asA[List[String]]  // failure
      vs ::= v.open_!
      v match {
        case Full(`CurVersion`) => return Full(vs)
        case Full("0") => createTablesVersion0()
        case Full(version) => upgradeFrom(version)
        case Empty => assErr("[debiki_error_3532kwrf")
        case f: Failure => return f
      }
    }
    assErr("Eternal upgrade loop [debiki_error_783STRkfsn]")
  }

  // COULD lock DW_VERSION when upgrading, don't upgrade if it's locked
  // by someone else.

  /** Creates tables in an empty schema. */
  def createTablesVersion0(): Box[String] = {
    for {
      ok <- exUp("""
              create table DW_VERSION(
                VERSION NVARCHAR2(2000))
              """)
      ok <- exUp("""
              insert into DW_VERSION(VERSION) values ('0.0.2')
              """)
    } yield
      "0.0.2"
  }

  private def upgradeFrom(version: String): Box[String] = {
    unimplemented("There's no version but 0.0.2")
  }

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
