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

CAPITALIZE table, column and constraint names but use lowercase for SQL
keywords. Then one can easily find all occurrences of a certain table or
column name, by searching for the capitalized name, e.g. the "TIME" column.
If you, however, use lowercase names, then you will find lots of irrelevant
occurrances of "time".

*****************

Could delete old SQL / Scala from this file.
Could rename TENANT.ID to TID. (can be searched for, no false hits)
Could rename NEW_IP --> LOGIN_NEW_IP


Create a user like so: (the tablespace and user name can be anything)

create profile DEBIKI limit idle_time 20;

CREATE USER "DEBIKI_TEST"
  PROFILE "DEBIKI"
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
  SNO         sequence number
  TENANT
  PAGE        foreign key to DW_PAGE.PK
  ID
  TYPE
  TIME
  WHO
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
  ACTION_SNO  foreign key to DW_ACTIONS.SNO
  TAG

(DW0_VALS
  PK
  VALUE)


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
    // COULD add:
    // constraint DW0_PAGES_GUID__C
    //  check (regexp_like (GUID, '[0-9a-z_]+', 'c')),
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
      // COULD add a CANONICAL = T/NULL column, which is T if PATH is the
      // path in the canonical URL. Then, TENANT + PAGE-guid + CANONICAL
      // would be a unique key. TENANT + PAGE-guid + NULL could however
      // occur many times and the server would then redirect the browser to
      // the canonical URL. -- no? it seems that results in a unique key
      // violation, just tested the same thing with
      //                                TENANT+PARENT+NAME+GUID_IN_PATH.
      //
      // GUID_HIDDEN = 'T' prevents pages with the same GUID from having
      // the same PARENT+NAME.
      // If GUID_HIDDEN is NULL, then the constraint DW0_PATHS_TENANT_PATH__U
      // has no effect, so when the guid is *not* hidden (but instead included
      // in the path: /parent/-guid-name/), then TENANT+PARENT+NAME need not
      // be unique.
      ok <- exUp("""
        create table DW0_PAGEPATHS(
          TENANT nvarchar2(100) not null,
          PARENT nvarchar2(100) not null,
          PAGE_GUID nvarchar2(100) not null,
          PAGE_NAME nvarchar2(100) not null,
          GUID_IN_PATH nvarchar2(100) not null,
          constraint DW0_PAGEPATHS_TENANT_GUID__P
              primary key (TENANT, PAGE_GUID),
          constraint DW0_PAGEPATHS_TENANT_PATH__U
              unique (TENANT, PARENT, PAGE_NAME, GUID_IN_PATH),
          constraint DW0_PAGEPATHS__R__PAGES
              foreign key (TENANT, PAGE_GUID)
              references DW0_PAGES (TENANT, GUID) deferrable,
          constraint DW0_PAGEPATHS_PARENT__C
              check (PARENT not like '%/-%'), -- '-' means guid follows
          constraint DW0_PAGEPATHS_GUIDINPATH__C
              check (GUID_IN_PATH in (' ', PAGE_GUID))
        )
        """)
      // (( I think there is an index on the foreign key columns TENANT and
      // PAGE, because Oracle creates an index on the primary key columns
      // TENANT, PAGE (and ID). Without this index, the whole DW0_ACTIONS
      // table would be locked, if a transaction updated a row in DW0_PAGES.
      // (Google for "index foreign key oracle")  ))

//      alter table DW0_ACTIONS modify (TYPE nchar(4));
//
//      alter table DW0_ACTIONS add constraint DW0_ACTIONS_TYPE__C2
//          check (TYPE in ('Post', 'Rtng', 'Edit', 'EdAp', 'EdRv'))
//
//      alter table DW0_ACTIONS drop constraint DW0_ACTIONS_TYPE__C
//      alter table DW0_ACTIONS rename constraint DW0_ACTIONS_TYPE__C2 to DW0_ACTIONS_TYPE__C
//
//  ---
//      alter table DW0_ACTIONS modify  constraint DW0_ACTIONS_TYPE__C
//              check (TYPE in ('Post', 'Edit', 'EdAp', 'EdRe', 'Rtng'))
//
//              alter table DW0_ACTIONS add TYPE_ nchar2(4);
//              update DW0_ACTIONS set TYPE_ = TYPE;
//              alter table DW0_ACTIONS rename  ...
//              alter table DW0_ACTIONS drop  ...
//  ---

      // UNTESTED the SNO column
      ok <- exUp("""
        create table DW0_ACTIONS(
          SNO number(20)        constraint DW0_ACTIONS_SNO__N not null,
          TENANT nvarchar2(100) constraint DW0_ACTIONS_TENANT__N not null,
          PAGE nvarchar2(100)   constraint DW0_ACTIONS_PAGE__N not null,
          ID nvarchar2(100)     constraint DW0_ACTIONS_ID__N not null,
          TYPE nchar(4)         constraint DW0_ACTIONS_TYPE__N not null,
          TIME timestamp        constraint DW0_ACTIONS_TIME__N not null,
          WHO nvarchar2(100)    constraint DW0_ACTIONS_WHO__N not null,
          IP nvarchar2(100)     constraint DW0_ACTIONS_IP__N not null,
          RELA nvarchar2(100),
          DATA nclob,
          DATA2 nclob,
          constraint DW0_ACTIONS_SNO__U
              unique key (SNO),
          constraint DW0_ACTIONS__P
              primary key (TENANT, PAGE, ID),
          constraint DW0_ACTIONS__R__PAGES
              foreign key (TENANT, PAGE)
              references DW0_PAGES (TENANT, GUID) deferrable,
          constraint DW0_ACTIONS_RELA__R__ACTIONS
              foreign key (TENANT, PAGE, RELA)
              references DW0_ACTIONS (TENANT, PAGE, ID) deferrable,
          constraint DW0_ACTIONS_TYPE__C
              check (TYPE in ('Post', 'Edit', 'EdAp', 'EdRe', 'Rtng'))
        )
        """)

      // UNTESTED
      ok <- exUp("""
        create sequence DW0_ACTIONS_SNO start with 1
        """)

      ok <- exUp("""
        create table DW0_RATINGS(
          ACTION_SNO number(20) constraint DW0_RATINGS_ASNO__N not null,
          TAG nvarchar2(100)    constraint DW0_RATINGS_TAG__N not null,
          constraint DW0_RATINGS__R__ACTIONS
              foreign key (ACTION_SNO)
              references DW0_ACTIONS(SNO) deferrable,
          constraint DW0_RATINGS__P
              primary key (ACTION_SNO, TAG)
        )
        """)

       ok <- exUp("""
        create table DW0_VERSION(
          VERSION nvarchar2(100) constraint DW0_VERSION_VERSION__N not null
        )
        """)
      ok <- exUp("""
        insert into DW0_VERSION(VERSION) values ('0.0.2')
        """)  // BUG? I've forgotten to commit?
    } yield
      "0.0.2"
  }

  private def upgradeFrom(version: String): Box[String] = {
    unimplemented("There's no version but 0.0.2")
  }

}

/* Add DW0_RATINGS:

  alter table DW0_ACTIONS add SNO number(20)
      constraint DW0_ACTIONS_SNO__U unique;

  update DW0_ACTIONS set SNO = (
    select rno from (
      select rownum rno, rid from (
        select rowid rid from DW0_ACTIONS order by TIME, TYPE desc))
  where DW0_ACTIONS.rowid = rid);

  alter table DW0_ACTIONS modify (SNO constraint DW0_ACTIONS_SNO__N not null);

  create table DW0_RATINGS(
          ACTION_SNO number(20) constraint DW0_RATINGS_ASNO__N not null,
          TAG nvarchar2(100)    constraint DW0_RATINGS_TAG__N not null,
        constraint DW0_RATINGS__R__ACTIONS
            foreign key (ACTION_SNO)
            references DW0_ACTIONS(SNO) deferrable,
          constraint DW0_RATINGS__P
            primary key (ACTION_SNO, TAG)
        );

  declare
    sno number;
  begin
    select max(SNO) + 1 into sno from DW0_ACTIONS;
    execute immediate 'create sequence DW0_ACTIONS_SNO start with '|| sno;
  end;
  /

Migration script from DW0 to DW1
-----------------

Leave DW0_VERSION as is.

insert into DW1_PAGES (TENANT, SNO, GUID)
select '2' TENANT, DW1_PAGES_SNO.nextval SNO, GUID from DW0_PAGES;

insert into DW1_PATHS (TENANT, FOLDER, PAGE_GUID, PAGE_NAME, GUID_IN_PATH)
select
  '2' TENANT, PARENT FOLDER, PAGE_GUID, PAGE_NAME,
    case when GUID_IN_PATH = ' ' then 'F' else 'T' end GUID_IN_PATH
from DW0_PAGEPATHS;

execute dbms_errlog.create_error_log('DW1_IDS_SIMPLE', 'ERRLOG');

insert into DW1_IDS_SIMPLE(SNO, NAME, EMAIL, LOCATION, WEBSITE)
select DW1_IDS_SNO.nextval,
  NVL(REGEXP_SUBSTR(WHO,'[^|]+', 1, 2), '-') NAME,
  NVL(REGEXP_SUBSTR(WHO,'[^|]+', 1, 3), '-') EMAIL,
  '-' LOCATION,
  NVL(REGEXP_SUBSTR(WHO,'[^|]+', 1, 4), '-') WEBSITE
from DW0_ACTIONS
log errors into ERRLOG reject limit 99999;

insert into DW1_LOGINS(SNO, TENANT, PREV_LOGIN, ID_TYPE, ID_SNO,
                      LOGIN_IP, LOGIN_TIME, LOGOUT_IP, LOGOUT_TIME)
  select
    DW1_LOGINS_SNO.nextval SNO,
    '2' TENANT,
    cast (null as number(20,0)) PREV_LOGIN,
    'Simple' ID_TYPE,
    (select SNO from DW1_IDS_SIMPLE where
      NAME = NVL(REGEXP_SUBSTR(A.WHO,'[^|]+', 1, 2), '-') and
      EMAIL = NVL(REGEXP_SUBSTR(A.WHO,'[^|]+', 1, 3), '-') and
      LOCATION = '-' and
      WEBSITE = NVL(REGEXP_SUBSTR(A.WHO,'[^|]+', 1, 4), '-'))
      ID_SNO,
    cast(A.IP as VARCHAR2(39 BYTE)) LOGIN_IP,
    A.TIME LOGIN_TIME,
    cast(NULL as VARCHAR2(39 BYTE)) LOGOUT_IP,
    cast(NULL as timestamp) LOGOUT_TIME
  from DW0_ACTIONS A;


insert into DW1_PAGE_ACTIONS(
    PAGE,
    PAID,
    LOGIN,
    TIME,
    TYPE,
    RELPA,
    TEXT,
    MARKUP,
    WHEERE,
    NEW_IP,
    DESCR)
select
  (select p.SNO from DW1_PAGES p where p.GUID = a.PAGE and p.TENANT = '2'
    ) PAGE,  -- sno
  case when cast (a.ID as varchar2(30)) = '0' then '1' else cast (a.ID as varchar2(30)) end
    PAID,
  (select SNO from DW1_LOGINS
    where
      TENANT = '2' and
      ID_TYPE = 'Simple' and
      ID_SNO = (
        select SNO from DW1_IDS_SIMPLE where
          NAME = NVL(REGEXP_SUBSTR(A.WHO,'[^|]+', 1, 2), '-') and
          EMAIL = NVL(REGEXP_SUBSTR(A.WHO,'[^|]+', 1, 3), '-') and
          LOCATION = '-' and
          WEBSITE = NVL(REGEXP_SUBSTR(A.WHO,'[^|]+', 1, 4), '-')
        ) and
      LOGIN_IP = a.IP and
      LOGIN_TIME = a.TIME
    ) LOGIN,
  a.TIME TIME,
  case cast (a.TYPE as varchar2(10)) when 'Post' then 'Post'
              when 'Edit' then 'Edit'
              when 'EdAp' then 'EditApp'
              when 'Rtng' then 'Rating' end
    TYPE,
  case when (a.RELA is null or -- this is the root post, RELPA should = ID = 1
                cast (a.RELA as varchar2(30)) = '0' -- root post id 0 ...
              ) then '1'                            -- ... was changed to 1
        else cast (a.RELA as varchar2(30)) end
    RELPA,
  a.DATA TEXT,
  '' MARKUP,
  '' WHEERE,
  '' NEW_IP,
  '' DESCR
from DW0_ACTIONS a;


insert into DW1_PAGE_RATINGS(PAGE, PAID, TAG)
select a1.PAGE, a1.PAID, r0.TAG
from DW0_RATINGS r0, DW0_ACTIONS a0, DW1_PAGE_ACTIONS a1, DW1_PAGES p1
where a0.SNO = r0.ACTION_SNO
  and p1.GUID = a0.PAGE
  and a1.PAGE = p1.SNO
  and a1.PAID = cast(a0.ID as varchar(30));


-- Views:
select l.SNO N, l.TENANT T, l.ID_TYPE TYPE, l.LOGIN_TIME TIME, l.LOGIN_IP IP, s.NAME, s.EMAIL, s.LOCATION LOC, s.WEBSITE, l.LOGOUT_IP, l.LOGOUT_TIME from DW1_LOGINS l, DW1_IDS_SIMPLE s
where l.ID_TYPE = 'Simple' and l.ID_SNO = s.SNO;




Add DW1 tables
-----------------

drop table DW1_PAGE_RATINGS;
drop table DW1_PAGE_ACTIONS;
drop table DW1_PATHS;
drop table DW1_PAGES;
drop table DW1_LOGINS;
drop table DW1_IDS_SIMPLE;
drop table DW1_IDS_OPENID;
drop table DW1_USERS;
drop table DW1_TENANT_HOSTS;
drop table DW1_TENANTS;
drop sequence DW1_TENANTS_ID;
drop sequence DW1_USERS_SNO;
drop sequence DW1_LOGINS_SNO;
drop sequence DW1_IDS_SNO;
drop sequence DW1_PAGES_SNO;


create table DW1_TENANTS(
  ID varchar2(10) not null,
  NAME nvarchar2(100) not null,
  CTIME timestamp default systimestamp not null,
  constraint DW1_TENANTS_ID__P primary key (ID),
  constraint DW1_TENANTS_NAME__U unique (NAME),
  constraint DW1_TENANTS_ID_NOT_0__C check (ID <> '0')
);

-- The tenant id is a varchar2, although it's currently assigned to from
-- this number sequence.
create sequence DW1_TENANTS_ID start with 10;


-- Host addresses that handle requests for tenants. A tenant might be
-- reachable via many addresses (e.g. www.debiki.se and debiki.se).
-- One host is the canonical/primary host, and all other hosts currently
-- redirect to that one. (In the future, could add a column that flags that
-- a <link rel=canonical> is to be used instead of a HTTP 301 redirect.)
--
create table DW1_TENANT_HOSTS(
  TENANT varchar2(10) not null,
  HOST nvarchar2(50) not null,
  CANONICAL varchar2(10),
  -- HTTPS values: `Required': http redirects to https, `Allowed': https
  -- won't redirect to http, but includes a <link rel=canonical>.
  -- `No': https times out or redirects to http.
  HTTPS varchar2(10),
  CTIME timestamp default systimestamp not null,
  MTIME timestamp default systimestamp not null,
  constraint DW1_TNTHSTS__R__TENANTS
      foreign key (TENANT)
      references DW1_TENANTS(ID),
  constraint DW1_TNTHSTS_HOST__U unique (HOST),
  constraint DW1_TNTHSTS_CNCL__C check (CANONICAL in ('T', 'F')),
  constraint DW1_TNTHSTS_HTTPS__C check (HTTPS in ('Required', 'Allowed', 'No'))
);

-- Make sure there's only one canonical host per tenant.
create unique index DW1_TNTHSTS_TENANT_CNCL__U on DW1_TENANT_HOSTS(
    case when CANONICAL = 'T' then TENANT else NULL end,
    case when CANONICAL = 'T' then 'T' else NULL end);

-- Create a Local tenant that redirects 127.0.0.1 to `localhost'.
insert into DW1_TENANTS(ID, NAME) values ('1', 'Local');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, 'localhost:8080', 'T', 'No');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, '127.0.0.1:8080', 'F', 'No');
commit;


-- A user is identified by its SNO. You cannot trust the specified name or
-- email, not even with OpenID or OpenAuth login. This means it's *not*
-- possible to lookup a user in this table, given e.g. name and email.
-- Instead, you need to e.g. lookup DW1_IDS_OPENID.OID_CLAIMED_ID,
-- to find _OPENID.USR, which is the relevant user SNO.
-- Many _CLAIMED_ID might point to the same _USERS row, because users can
-- (not implemented) merge different OpenID accounts to one single account.
--
-- The name/email/etc columns are currently always empty! The name/email from
-- the relevant identity (e.g. DW1_IDS_OPENID.FIRST_NAME) is used instead.
-- However, if the user manually fills in (not implemented) her user data,
-- then those values will take precedence (over the one from the
-- identity provider). -- In the future, if one has mapped
-- many identities to one single user account, the name/email/etc from
-- DW1_USERS will be used, rather than identity specific data,
-- because we wouldn't know which identity to use.
--
-- So most _USERS rows are essentially not used. However I think I'd like
-- to create those rows anyway, to reserve a user id for the identity.
-- (If the identity id was used, and a user row was later created,
-- then we'd probably stop using the identity id and use the user id instead,
-- then the id for that real world person would change, perhaps bad? So
-- reserve and use a user id right away, on identity creation.)
--
create table DW1_USERS(
  TENANT varchar2(10)         not null,
  SNO number(20)              not null,
  DISPLAY_NAME nvarchar2(100),  -- currently empty, always (2011-09-17)
  EMAIL nvarchar2(100),
  COUNTRY nvarchar2(100),
  WEBSITE nvarchar2(100),
  SUPERADMIN char(1),
  constraint DW1_USERS_TNT_SNO__P primary key (TENANT, SNO),
  constraint DW1_USERS_SUPERADM__C check (SUPERADMIN in ('T')),
  constraint DW1_USERS__R__TENANT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  -- No unique constraint on (TENANT, DISPLAY_NAME, EMAIL, SUPERADMIN).
  -- People's emails aren't currently verified, so people can provide
  -- someone else's email. So need to allow many rows with the same email.
  constraint DW1_USERS_SNO_NOT_0__C check (SNO <> 0)
);

create sequence DW1_USERS_SNO start with 10;

-- create index DW1_USERS_TNT_NAME_EMAIL
--  on DW1_USERS(TENANT, DISPLAY_NAME, EMAIL);


-- DW1_LOGINS isn't named _SESSIONS because I'm not sure a login and logout
-- defines a session? Cannot a session start before you login?
create table DW1_LOGINS(  -- logins and logouts
  SNO number(20)             not null,
  TENANT varchar2(10)        not null,
  PREV_LOGIN number(20),
  -- COULD replace ID_TYPE/_SNO with: ID_SIMPLE, ID_OPENID, ID_TWITTER, etc,
  -- require that exactly one be non-NULL, and create foreign keys to the ID
  -- tables. That would have avoided a few bugs! and more future bugs too!?
  ID_TYPE varchar2(10)       not null,
  ID_SNO number(20)          not null,
  LOGIN_IP varchar2(39)      not null,
  LOGIN_TIME timestamp       not null,
  LOGOUT_IP varchar2(39),
  LOGOUT_TIME timestamp,
  constraint DW1_LOGINS_SNO__P primary key (SNO),
  constraint DW1_LOGINS_TNT__R__TENANTS
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_LOGINS__R__LOGINS
      foreign key (PREV_LOGIN)
      references DW1_LOGINS(SNO) deferrable,
  constraint DW1_LOGINS_IDTYPE__C
      check (ID_TYPE in ('Simple', 'OpenID')),
  constraint DW1_LOGINS_SNO_NOT_0__C check (SNO <> 0)
) PCTFREE 40;
-- The default PCTFREE is 10%. LOGOUT_IP and LOGOUT_TIME will be
-- updated for users that click Log Out, or logs in as other users.
-- If that happens frequently, these 10% might be too small,
-- resulting in row chaining.
-- How many bytes does a row occupy?
--  3 * 21 (number) + 10 (usertype), 39 (start ip)
--  11 (timestamp) = 63 + 10 + 39 + 11 = 133.
-- Grows w 39 + 11 = 50 bytes ~= 40% if updated with LOGOUT_IP and LOGOUT_TIME.
-- So if PCTFREE is set to 40%, there'll be no row chaining?

create index DW1_LOGINS_TNT on DW1_LOGINS(TENANT);
create index DW1_LOGINS_PREVL on DW1_LOGINS(PREV_LOGIN);

create sequence DW1_LOGINS_SNO start with 10;

-- For usage by both _IDS_SIMPLE and _OPENID, so a given identity-SNO
-- is found in only one of _SIMPLE and _OPENID.
create sequence DW1_IDS_SNO start with 10;


-- Simple login identities (no password needed).
-- When loaded from database, a dummy User is created, with its id
-- set to -SNO (i.e. "-" + SNO). Users with ids starting with "-"
-- are thus unauthenticadet users (and don't exist in DW1_USERS).
create table DW1_IDS_SIMPLE(
  SNO number(20)          not null,
  NAME nvarchar2(100)     not null,
  EMAIL nvarchar2(100)    not null,
  LOCATION nvarchar2(100) not null,
  WEBSITE nvarchar2(100)  not null,
  constraint DW1_IDSSIMPLE_SNO__P primary key (SNO),
  constraint DW1_IDSSIMPLE__U unique (NAME, EMAIL, LOCATION, WEBSITE),
  constraint DW1_IDSSIMPLE_SNO_NOT_0__C check (SNO <> 0)
);

-- (Uses sequence nunmber from DW1_IDS_SNO.)


-- OpenID identities.
--
-- The same user might log in to different tenants.
-- So there might be many (TENANT, _CLAIMED_ID) with the same _CLAIMED_ID.
-- Each (TENANT, _CLAIMED_ID) points to a DW1_USER row.
--
-- If a user logs in with a claimed_id already present in this table,
-- but e.g. with modified name and email, the FIRST_NAME and EMAIL columns
-- are updated to store the most recent name and email attributes sent by
-- the OpenID provider. (Could create a _OPENID_HIST  history table, where old
-- values are remembered. Perhaps useful if there's some old email address
-- that someone used long ago, and now you wonder whose address was it?)
-- Another solution: Splitting _OPENID into one table with the unique
-- tenant+claimed_id and all most recent attribute values, and one table
-- with the attribute values as of the point in time of the OpenID login.
--
create table DW1_IDS_OPENID(
  SNO number(20)                  not null,
  TENANT varchar2(10)             not null,
  -- When an OpenID identity is created, a User is usually created too.
  -- It is stored in USR_ORIG. However, to allow many OpenID identities to
  -- use the same User (i.e. merge OpenID accounts, and perhaps Twitter,
  -- Facebok accounts), each _OPENID row can be remapped to another User.
  -- This is done via the USR row (which is = USR_ORIG if not remapped).
  -- (Not yet implemented though: currently USR = USR_ORIG always.)
  USR number(20)                  not null,
  USR_ORIG number(20)             not null,
  OID_CLAIMED_ID nvarchar2(500)   not null, -- Google's ID hashes 200-300 long
  OID_OP_LOCAL_ID nvarchar2(500)  not null,
  OID_REALM nvarchar2(100)        not null,
  OID_ENDPOINT nvarchar2(100)     not null,
  -- The version is a URL, e.g. "http://specs.openid.net/auth/2.0/server".
  OID_VERSION nvarchar2(100)      not null,
  FIRST_NAME nvarchar2(100)       not null,
  EMAIL nvarchar2(100)            not null,
  COUNTRY nvarchar2(100)          not null,
  constraint DW1_IDSOID_SNO__P primary key (SNO),
  constraint DW1_IDSOID_TNT_OID__U
      unique (TENANT, OID_CLAIMED_ID),
  constraint DW1_IDSOID_USR_TNT__R__USERS
      foreign key (TENANT, USR)
      references DW1_USERS(TENANT, SNO) deferrable,
  constraint DW1_IDSOID_SNO_NOT_0__C check (SNO <> 0)
);

create index DW1_IDSOID_TNT_USR on DW1_IDS_OPENID(TENANT, USR);
create index DW1_IDSOID_EMAIL on DW1_IDS_OPENID(EMAIL);

-- (Uses sequence nunmber from DW1_IDS_SNO.)


-- Later: DW1_USER_ACTIONS?


-- (How do we know who created the page? The user who created
-- the root post, its page-action-id is always "1".)
create table DW1_PAGES(
  SNO number(20)        not null,
  TENANT varchar2(10)   not null,
  GUID varchar2(50)     not null,
  constraint DW1_PAGES_SNO__P primary key (SNO),
  constraint DW1_PAGES__U unique (TENANT, GUID),
  constraint DW1_PAGES__R__TENANT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_PAGES_SNO_NOT_0__C check (SNO <> 0)
);

create sequence DW1_PAGES_SNO start with 10;



-- Contains all posts, edits, ratings etc, everything that's needed to
-- render a discussion.
create table DW1_PAGE_ACTIONS(
  PAGE number(20)      not null,
  PAID varchar2(30)    not null,  -- page action id
  LOGIN number(20)     not null,
  TIME timestamp       not null,
  TYPE varchar2(10)    not null,
  RELPA varchar2(30)   not null,  -- related page action
  -- STATUS char(1)    not null,  -- check in 'S', 'A' -- suggestion/applied
  TEXT nclob,
  MARKUP nvarchar2(30),
  WHEERE nvarchar2(150),
  NEW_IP varchar2(39), -- null, unless differs from DW1_LOGINS.START_IP
  DESCR nvarchar2(1001), -- remove? use a new Post instead, to descr this action
  constraint DW1_PACTIONS_PAGE_PAID__P primary key (PAGE, PAID),
  constraint DW1_PACTIONS__R__LOGINS
      foreign key (LOGIN)
      references DW1_LOGINS (SNO) deferrable,
  constraint DW1_PACTIONS__R__PAGES
      foreign key (PAGE)
      references DW1_PAGES (SNO) deferrable,
  constraint DW1_PACTIONS__R__PACTIONS
      foreign key (PAGE, RELPA) -- no index: no deletes/upds in parent table
                         -- and no joins (loading whole page at once instead)
      references DW1_PAGE_ACTIONS (PAGE, PAID) deferrable,
  constraint DW1_PACTIONS_TYPE__C
      check (TYPE in ('Post', 'Edit', 'EditApp', 'Rating',
                      'Revert', 'NotfReq', 'NotfSent', 'NotfRep')),
  -- There must be no action with id 0; let 0 mean nothing.
  constraint DW1_PACTIONS_PAID_NOT_0__C
      check (PAID <> '0'),
  -- The root post has id 1 and it must be its own parent.
  constraint DW1_PACTIONS_ROOT_IS_1__C
      check (RELPA = case when PAID = '1' then '1' else RELPA end),
  -- The root post must be a 'Post'.
  constraint DW1_PACTIONS_ROOT_IS_POST__C
      check (TYPE = case when PAID = '1' then 'Post' else TYPE end)
) --
  -- Create an index organized table, but place edit DESCR-iptions in
  -- the overflowsegment (i.e. not in the index) since they're only accessed
  -- when someone decides to edit a Post:
  organization index including NEW_IP  -- (but excluding DESCR)
  -- By default, a (C)LOB is stored inline, if it's less than approximately
  -- 4000 bytes. Unless the table is an index organized table. Then you
  -- should:
  lob (TEXT) store as (enable storage in row)
  -- and you need to:
  overflow;
  -- Read about LOB:s and IOT:s here:
  -- http://download.oracle.com/docs/cd/B28359_01/appdev.111/b28393/
  --    adlob_tables.htm#i1006887
  -- Quotes: "By default, all LOBs in an index organized table created
  --        without an overflow segment will be stored out of line."
  --  and: "if an overflow segment has been specified, then LOBs in index
  --    organized tables will exactly mimic their semantics in conventional
  --    tables"
  -- More IOT links:
  --  http://www.orafaq.com/wiki/Index-organized_table
  --  http://tkyte.blogspot.com/2007/02/i-learn-something-new-every-day.html

-- Needs an index on LOGIN: it's an FK to DW1_LOINGS, whose END_IP/TIME is
-- updated at the end of the session.
create index DW1_PACTIONS_LOGIN on DW1_PAGE_ACTIONS(LOGIN);


create table DW1_PAGE_RATINGS(
  PAGE number(20) not null,
  PAID varchar2(30) not null, -- page action id
  TAG nvarchar2(30) not null,
  constraint DW1_PRATINGS__P primary key (PAGE, PAID, TAG),
  constraint DW1_PRATINGS__R__PACTIONS
      foreign key (PAGE, PAID)
      references DW1_PAGE_ACTIONS(PAGE, PAID) deferrable
) organization index;


create table DW1_PATHS(
  TENANT varchar2(10) not null,
  FOLDER nvarchar2(100) not null,
  PAGE_GUID varchar2(30) not null,
  PAGE_NAME nvarchar2(100) not null,
  GUID_IN_PATH char(1) not null,
  constraint DW1_PATHS_TNT_PAGE__P primary key (TENANT, PAGE_GUID),
  constraint DW1_PATHS_TNT_PAGE__R__PAGES
      foreign key (TENANT, PAGE_GUID) -- indexed because of pk
      references DW1_PAGES (TENANT, GUID) deferrable,
  constraint DW1_PATHS_FOLDER__C
      check (FOLDER not like '%/-%'), -- '-' means guid follows
  constraint DW1_PATHS_GUIDINPATH__C
      check (GUID_IN_PATH in ('T', 'F'))
);

-- TODO: Test case: no 2 pages with same path
-- Create an index that ensures (tenant, folder, pagename) is unique,
-- if the page guid is not included in the path to the page.
-- That is, if the path is like:  /some/folder/pagename  (no guid in path)
-- rather than:  /some/folder/-<guid>-pagename  (guid included in path)
create unique index DW1_PATHS__U on DW1_PATHS(
    TENANT, FOLDER, PAGE_NAME,
    case when GUID_IN_PATH = 'T' then PAGE_GUID else 'F' end);



Add flags and deletions and meta to DW1 tables
-----------------

-- Let TYPE be 20 chars not 10, so "FlagCopyVio" fits.
alter table DW1_PAGE_ACTIONS modify (TYPE varchar2(20))

-- Add new action types.
alter table DW1_PAGE_ACTIONS rename constraint DW1_PACTIONS_TYPE__C
    to DW1_PACTIONS_TYPE__C_OLD;
alter table DW1_PAGE_ACTIONS add constraint DW1_PACTIONS_TYPE__C
check (TYPE in (
    'Post', 'Meta', 'Edit', 'EditApp', 'Rating',
    'DelPost', 'DelTree',
    'FlagSpam', 'FlagIllegal', 'FlagCopyVio', 'FlagOther'));
alter table DW1_PAGE_ACTIONS drop constraint DW1_PACTIONS_TYPE__C_OLD;



Apply edits — change Edit.TEXT format
-----------------

-- The Edit.TEXT format was changed to a diff.
-- So, before upgrading the appserver, apply all old edits:

-- Backup everything: posts, edits, ratings, etc.
create table DW1_PAGE_ACTIONS_BKP111101 as select * from DW1_PAGE_ACTIONS;

-- Update all Post:s to the most recent Edit applied.
update dw1_page_actions pa
set text = (select text from (
  select page, edit_id, post_id, text from (
    select
      e.page, e.paid edit_id, e.relpa post_id, e.text,
      e.time,
      max(a.time) over (partition by e.page, e.relpa) max_time
    from dw1_page_actions e, dw1_page_actions a
    where a.type = 'EditApp'
      and e.type = 'Edit'
      and a.relpa = e.paid
    order by e.page, e.relpa, e.time desc
  )
  where time = max_time
) neew
where neew.post_id = pa.paid
  and neew.page = pa.page
)
where pa.type = 'Post'
  -- Only update posts that have been edited (or other post texts
  -- would be NULLed).
  and exists (
    select 1 from dw1_page_actions e, dw1_page_actions a
    where e.relpa = pa.paid
      and e.page = pa.page
      and e.type = 'Edit'
      and a.relpa = e.paid
      and a.page = e.page
      and a.type = 'EditApp'
  )
;

-- Delete all edits — since we've already applied them.
-- First delete [any DelPost/Tree that deletes an edit] though.
delete from dw1_page_actions a
where a.type in ('DelPost', 'DelTree')
  and exists (select * from dw1_page_actions a2
              where a.relpa = a2.paid
               and a2.type in ('Edit', 'EditApp')
               );
delete from dw1_page_actions where type in ('Edit', 'EditApp');

commit;


Misc
-----------------

-- The description will have to be a separate Post instead.
alter table DW1_PAGE_ACTIONS drop column DESCR;


Views
-----------------
(Don't use for anything but statistics right now. Don't
base Scala code on any views.)

-- Most recent posts
--    ('Post', 'Rtng', 'Edit', 'EdAp', 'EdRv')
-- Flagged posts
-- Edit suggestions

-- Page tree:
--  - Pages per directory
--     New pages - sort by ctime
--  - Posts per page
--  - Users per page

-- New authenticated users
-- New anonymous users

-- Posts by user
-- & links to everything
-- & filters


-- Login identities
create or replace view dw1v_logins as
select l.tenant tid, l.sno lid, l.login_time, l.logout_time,
    l.login_ip, l.prev_login, l.id_type, o.sno idid, o.first_name name,
    o.email, o.country loc, N'-' url
  from dw1_logins l, dw1_ids_openid o
  where l.id_type = 'OpenID'
    and l.id_sno = o.sno
union
select l.tenant tid, l.sno lid, l.login_time, l.logout_time,
    l.login_ip, l.prev_login, l.id_type, s.sno idid, s.name,
    s.email, s.location loc, s.website url
  from dw1_logins l, dw1_ids_simple s
  where l.id_type = 'Simple'
    and l.id_sno = s.sno
order by login_time;


-- All identities
create or replace view dw1v_ids_all as
select 'OpenID' id_type, o.sno idid, o.usr usrid, o.first_name name,
    o.email, o.country loc, N'-' url
  from dw1_ids_openid o
union
select 'Simple' id_type, sno idid, -sno usrid, name,
    email, location loc, website url
  from dw1_ids_simple;


-- Page actions
create or replace view dw1v_page_actions as
select
  t.name tenant,
  pt.folder, pt.page_name, pt.page_guid, pt.guid_in_path gip,
  -- pt.folder || case pt.guid_in_path
  --      when 'T' then '-'||pt.page_guid|| '-' || pt.page_name
  --      else pt.page_name ||' ('|| pt.page_guid ||')'
  --      end
  --   path,
  pg.sno page_sno,
  lg.lid, lg.name, lg.email, lg.id_type, lg.idid,
  a.time, a.type, a.paid, a.relpa, a.text, a.wheere, a.new_ip
from dw1_page_actions a, dw1_pages pg, dw1_paths pt, dw1_tenants t,
  dw1v_logins lg
where
  a.page = pg.sno and
  pt.page_guid = pg.guid and
  t.id = pg.tenant and
  a.login = lg.lid;


create or replace view dw1v_pages as
--with posts_per_page as (
--  select page page_sno, count(*) num from dw1_page_actions where type = 'Post'
--)
select
  folder, page_name, gip, page_guid, page_sno,
  count(distinct idid) n_ids,
  count(*) n_as,
  max(time) mtime,
  min(time) ctime
  -- posts_per_page.num
from dw1v_page_actions -- , posts_per_page
--where posts_per_page.page_sno = dw1v_page_actions.page_sno
group by folder, page_name, gip, page_guid, page_sno
;





*/


// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
