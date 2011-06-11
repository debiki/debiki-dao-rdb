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

/*

*** Read this ***

CAPITALIZE table, column and conotrtaint names but use lowercase for SQL
keywords. Then one can easily find all occurrences of a certain table or
column name, by searching for the capitalized name, e.g. the "TIME" column.
If you, however, use lowercase names, then you will find lots of irrellevant
occurrances of "when".

*****************


Create a user like so: (the tablespace and user name can be anything)

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

For now, just a few tables, not normalized (just to get started quickly).

"DW0_TABLE" means a Debiki ("DW_") version 0 ("0") table named "TABLE",
When upgrading, one can copy data to new tables, i.e. DW<X>_TABLE instead of
modifying data in the current tables. Then it's almost impossible to
corrupt any existing data.
  --- and if you don't need to upgrade, then keep the DW0_TABLE tables.
  (and let any new DW<X>_TABLE refer to DW0_TABLE).

DW0_PAGES    Description
  TENANT       The tenant
  GUID         Globally unique identifier.
                But a certain page might have been copied to another tenant,
                so the tenant is also part of the primary key, If the GUID
                is identical, it's the same page thoug.
  (created at, by? not needed, check the root post instead?)

DW0_ACTIONS  Description
  TENANT
  PAGE
  ID           The id of this action is unique within the page only.
  TYPE         Post / Edit / EditApp / Rating.
  TIME         Creation timestamp
  WHO          User
  IP
  RELA         related action (which post/edit to reply/edit/rate/apply)
  DATA         post-text / edit-diff / edit-app-result / rating-tags
  DATA2        post-where / edit-description / edit-app-debug / empty

DW0_USERS (todo ...)
  PK           locally generated sequence number, primary key
  NAME
  EMAIL
  URL

DW0_VERSION
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

  private def exUp(sql: String) = oradb.updateAtnms(sql, Nil)
  private def exQy = oradb.queryAtnms _

  /* All upgrades done, by this server instance, to convert the
   * database schema to the most recent version.
   */
  private var _upgrades: List[String] = Nil
  def upgrades = _upgrades

  def readCurVersion(): Box[String] = {
    for {
      ok <- oradb.queryAtnms("""
          select count(*) c from USER_TABLES
          where TABLE_NAME = 'DW0_VERSION'
          """,  Nil, rs => {
        val v = { rs.next; rs.getString("c") }
        if (v == "0") return Full("0")  // version 0 means an empty schema
        if (v == "1") Full("1")
        else Failure("[debiki_error_6rshk23r]")
      })
      v <- oradb.queryAtnms("""
          select VERSION v from DW0_VERSION
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
    var i = 3
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
    //  drop table DW0_VERSION;
    //  drop table DW0_ACTIONS;
    //  drop table DW0_PAGES;
    for {
      ok <- exUp("""
        create table DW0_PAGES(
          TENANT nvarchar2(100),
          GUID nvarchar2(100),
          constraint DW0_PAGES_TENANT_GUID__P primary key (TENANT, GUID)
        )
        """)
      // There might be many paths to one page. For example, if the page
      // is initialy created /some/where, and later moved /else/where, then
      // /some/where should redirect (not implemented though) to /else/where,
      // and DW0_PATHS will know this.
      // The canonical URL for a page is always serveraddr/0/-<guid>.
      ok <- exUp("""
        create table DW0_PATHS(
          TENANT nvarchar2(100),
          PATH nvarchar2(1000),
          PAGE nvarchar2(100),
          constraint DW0_PATHS_TENANT_PATH__P primary key (TENANT, PATH),
          constraint DW0_PATHS__R__PAGES
              foreign key (TENANT, PAGE)
              references DW0_PAGES (TENANT, GUID) deferrable
        )
        """)
      // (( I think there is an index on the foreign key columns TENANT and
      // PAGE, because Oracle creates an index on the primary key columns
      // TENANT, PAGE (and ID). Without this index, the whole DW0_ACTIONS
      // table would be locked, if a transaction updated a row in DW0_PAGES.
      // (Google for "index foreign key oracle")  ))
      ok <- exUp("""
        create table DW0_ACTIONS(
          TENANT nvarchar2(100),
          PAGE nvarchar2(100),
          ID nvarchar2(100)     constraint DW0_ACTIONS_ID__N not null,
          TYPE nvarchar2(100),
          TIME timestamp        constraint DW0_ACTIONS_TIME__N not null,
          WHO nvarchar2(100)    constraint DW0_ACTIONS_WHO__N not null,
          IP nvarchar2(100)     constraint DW0_ACTIONS_IP__N not null,
          RELA nvarchar2(100),
          DATA nclob,
          DATA2 nclob,
          constraint DW0_ACTIONS__P
              primary key (TENANT, PAGE, ID),
          constraint DW0_ACTIONS__R__PAGES
              foreign key (TENANT, PAGE)
              references DW0_PAGES (TENANT, GUID) deferrable,
          constraint DW0_ACTIONS_RELA__R__ACTIONS
              foreign key (TENANT, PAGE, RELA)
              references DW0_ACTIONS (TENANT, PAGE, ID) deferrable,
          constraint DW0_ACTIONS_TYPE__C
              check (TYPE in ('Post'))
        )
        """)
      ok <- exUp("""
        create table DW0_VERSION(
          VERSION nvarchar2(100) constraint DW0_VERSION_VERSION__N not null
        )
        """)
      ok <- exUp("""
        insert into DW0_VERSION(VERSION) values ('0.0.2')
        """)
    } yield
      "0.0.2"
  }

  private def upgradeFrom(version: String): Box[String] = {
    unimplemented("There's no version but 0.0.2")
  }

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
