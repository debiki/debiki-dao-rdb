/*

*** Read this ***

CAPITALIZE table, column and constraint names but use lowercase for SQL
keywords. Then one can easily find all occurrences of a certain table or
column name, by searching for the capitalized name, e.g. the "TIME" column.
If you, however, use lowercase names, then you will find lots of irrelevant
occurrances of "time".

Do not store the empty string. Store NULL instead.
((Reason: Oracle converts '' to NULL, so it's not possible to store
the empty string if you use Oracle. And Debiki is supposed to
support both PostgreSQL and Oracle.
Add constraints that check for the empty string -- name them
"...__C_NE" (see below).  ))

*****************

Naming standard
------------------
 "DW1_...__C" for check constraints,
        "__C_NE" check non empty (e.g.:  check (trim(COL) <> ''))
        "__C_N0" check not 0
        "__C_IN" check in (list of allowed values)
 "DW1_...__U" for unique constraints and unique indexes
 "DW1_...__R__..." for referential constraints
where "..." is "<abbreviated-table-name>_<abbreviated>_<column>_<names>".
E.g.:
  DW1_IDSMPLEML__R__LOGINS  -- a foreign key from IDS_SIMPLE_EMAIL to LOGINS
                            (column names excluded, Oracle allows 30 chars max)
  DW1_IDSMPLEML_EMAIL__C -- a check constraint on the EMAIL column
  DW1_IDSMPLEML_VERSION__U -- a unique constraint, which includes the VERSION
                              column (and it is the "interesting" column).
  DW1_USERS_TNT_NAME_EMAIL -- an index on USERS (TENANT, DISPLAY_NAME, EMAIL).

"DW0_TABLE" means a Debiki ("DW_") version 0 ("0") table named "TABLE",
When upgrading, one can copy data to new tables, i.e. DW<X>_TABLE instead of
modifying data in the current tables. Then it's almost impossible to
corrupt any existing data.
  --- and if you don't need to upgrade, then keep the DW0_TABLE tables.
  (and let any new DW<X>_TABLE refer to DW0_TABLE).

Foreign key indexes
------------------
When you add a foreign constraint, write the name of the corresponding
index in a comment to the right:
  constraint DW1_RLIBX__R__EMLOT  -- ix DW1_RLIBX_EMAILSENT
    foreign key ...

If there is no index, clarify why, e.g.:
  constraint DW1_RLIBX_TGTPGA__R__PGAS
      foreign key ...  -- no index: no deletes/upds in prnt tbl

Create a Debiki schema like so:
------------------
$ psql
=> create user debiki_test password 'apabanan454';
=> alter user debiki_test set search_path to '$user';
=> create database debiki_test owner debiki_test encoding 'UTF8';
$ psql --dbname debiki_test;
=> drop schema public;
=> create schema authorization debiki_test;

*/

/*
----- Reset schema

drop table DW0_VERSION;
drop table DW1_ROLE_INBOX;
drop table DW1_EMAILS_OUT;
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

*/

/* COULD rename abbreviations:
(so they would match those I use in Scala and CSS)

  PACTIONS --> PGAS

*/

----- Version

create table DW0_VERSION(
  VERSION varchar(100) constraint DW0_VERSION_VERSION__N not null
);

----- Tenants

-- Abbreviated: TNTS

create table DW1_TENANTS(
  ID varchar(32) not null,  -- COULD rename to GUID?
  NAME varchar(100) not null,
  CTIME timestamp default now() not null,
  constraint DW1_TENANTS_ID__P primary key (ID),
  constraint DW1_TENANTS_NAME__U unique (NAME),
  -- todo:  prod, dev, done: test -------
  -- alter table DW1_TENANTS drop constraint DW1_TENANTS_ID_NOT_0__C
  -- alter table DW1_TENANTS add
  constraint DW1_TNT_ID__C_NE check (trim(ID) <> ''),
  constraint DW1_TNT_ID__C_N0 check (ID <> '0'),
  constraint DW1_TNT_NAME__C_NE check (trim(NAME) <> '')
  ---- todo END ------------------
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
  TENANT varchar(32) not null,
  HOST varchar(50) not null,
  -- 'C'anonical: this host is the canonical host,
  -- 'R'edirect: this host redirects to the canonical host,
  -- 'L'ink: this host links to the canonical host via a <link rel=canonical>
  -- 'D'uplicate: this host duplicates the contents of the canonical host;
  --    it neither links nor redirects to the canonical host. Use for
  --    testing purposes only.
  CANONICAL varchar(1) not null,
  -- 'R'equired: http redirects to https
  -- 'A'llowed: https won't redirect to http, but includes a
  --    <link rel=canonical>.
  -- 'N'o: https times out or redirects to http.
  HTTPS varchar(1) default 'N' not null,
  CTIME timestamp default now() not null,
  MTIME timestamp default now() not null,
  constraint DW1_TNTHSTS__R__TENANTS  -- COULD create an FK index
      foreign key (TENANT)
      references DW1_TENANTS(ID),
  constraint DW1_TNTHSTS_HOST__U unique (HOST),
  constraint DW1_TNTHSTS_CNCL__C check (CANONICAL in ('C', 'R', 'L', 'D')),
  constraint DW1_TNTHSTS_HTTPS__C check (HTTPS in ('R', 'A', 'N'))
);

/* I made these changes later:
alter table DW1_TENANT_HOSTS alter CANONICAL set not null;
alter table DW1_TENANT_HOSTS alter CANONICAL type varchar(1);
alter table DW1_TENANT_HOSTS drop constraint DW1_TNTHSTS_CNCL__C;
update DW1_TENANT_HOSTS set CANONICAL = 'C' where CANONICAL = 'T';
update DW1_TENANT_HOSTS set CANONICAL = 'R' where CANONICAL = 'F';
alter table DW1_TENANT_HOSTS add
  constraint DW1_TNTHSTS_CNCL__C check (CANONICAL in ('C', 'R', 'L', 'D'));

alter table DW1_TENANT_HOSTS alter HTTPS set default 'N';
alter table DW1_TENANT_HOSTS alter HTTPS set not null;
alter table DW1_TENANT_HOSTS alter HTTPS type varchar(1);
alter table DW1_TENANT_HOSTS drop constraint DW1_TNTHSTS_HTTPS__C;
update DW1_TENANT_HOSTS set HTTPS = 'N';
alter table DW1_TENANT_HOSTS add constraint DW1_TNTHSTS_HTTPS__C check (
    HTTPS in ('R', 'A', 'N'));

drop index DW1_TNTHSTS_TENANT_CNCL__U;
-- Make sure there's only one canonical host per tenant.
create unique index DW1_TNTHSTS_TNT_CNCL__U on DW1_TENANT_HOSTS(TENANT)
where CANONICAL = 'C';
*/

-- Create a Local tenant that redirects 127.0.0.1 to `localhost'.
insert into DW1_TENANTS(ID, NAME) values ('1', 'Local');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, 'localhost2:8080', 'T', 'No');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, '127.0.0.1:8080', 'F', 'No');
-- commit; -- needs a `begin transaction'? or something?


----- Users

-- A user is identified by its SNO. You cannot trust the specified name or
-- email, not even with OpenID or OpenAuth login. This means it's *not*
-- possible to lookup a user in this table, given e.g. name and email.
-- Instead, you need to e.g. lookup DW1_IDS_OPENID.OID_CLAIMED_ID,
-- to find _OPENID.USR, which is the relevant user SNO.
-- Many _CLAIMED_ID might point to the same _USERS row, because users can
-- (not implemented) merge different OpenID accounts to one single account.
--
-- Currently only EMAIL_NOTFS is used.
-- The name/email columns are currently always null! The name/email from
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
-- Versioning: In the future, I'll add CTIME + LOGIN columns,
-- and the current version of a user will then be either the most recent row,
-- or all rows merged together in chronological order, where Null has no
-- effect.

create table DW1_USERS(  -- COULD rename to DW1_ROLES, abbreviated RLS
  TENANT varchar(32)          not null,
  SNO varchar(32)             not null,  -- COULD rename to GUID
  DISPLAY_NAME varchar(100),  -- currently null, always (2011-09-17)
  EMAIL varchar(100),
  COUNTRY varchar(100),
  WEBSITE varchar(100),
  SUPERADMIN varchar(1),
  constraint DW1_USERS_TNT_SNO__P primary key (TENANT, SNO),
  constraint DW1_USERS_SUPERADM__C check (SUPERADMIN in ('T')),
  constraint DW1_USERS__R__TENANT  -- ix DW1_USERS_TNT_SNO__P
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  -- No unique constraint on (TENANT, DISPLAY_NAME, EMAIL, SUPERADMIN).
  -- People's emails aren't currently verified, so people can provide
  -- someone else's email. So need to allow many rows with the same email.
  constraint DW1_USERS_SNO_NOT_0__C check (SNO <> '0')
);

create sequence DW1_USERS_SNO start with 10;

-- Email notifications: R = receive, N = do Not receive, F = forbid forever
alter table DW1_USERS add column EMAIL_NOTFS varchar(1);
alter table DW1_USERS add constraint DW1_USERS_EMLNTF__C
    check (EMAIL_NOTFS in ('R', 'N', 'F'));

-- Add missing constraints
update DW1_USERS set DISPLAY_NAME = null where DISPLAY_NAME = '';
update DW1_USERS set EMAIL = null where EMAIL = '';
update DW1_USERS set COUNTRY = null where COUNTRY = '';
update DW1_USERS set WEBSITE = null where WEBSITE = '';
alter table DW1_USERS add constraint DW1_USERS_DNAME__C
    check (DISPLAY_NAME <> '');
alter table DW1_USERS add constraint DW1_USERS_EMAIL__C
    check (EMAIL like '%@%.%');
alter table DW1_USERS add constraint DW1_USERS_COUNTRY__C
    check (COUNTRY <> '');
alter table DW1_USERS add constraint DW1_USERS_WEBSITE__C
    check (WEBSITE <> '');

-- create index DW1_USERS_TNT_NAME_EMAIL
--  on DW1_USERS(TENANT, DISPLAY_NAME, EMAIL);

-- DW1_LOGINS isn't named _SESSIONS because I'm not sure a login and logout
-- defines a session? Cannot a session start before you login?
create table DW1_LOGINS(  -- logins and logouts
  SNO varchar(32)            not null,  -- COULD rename to GUID
  TENANT varchar(32)         not null,
  PREV_LOGIN varchar(32),
  -- COULD replace ID_TYPE/_SNO with: ID_SIMPLE, ID_OPENID, ID_TWITTER, etc,
  -- require that exactly one be non-NULL, and create foreign keys to the ID
  -- tables. That would have avoided a few bugs! and more future bugs too!?
  ID_TYPE varchar(10)        not null,
  ID_SNO varchar(32)         not null,
  -- COULD add a USR --> DW1_USERS/ROLES column? so there'd be no need to
  -- join w/ all DW1_IDS_<whatever> tables when looking up the user for
  -- a certain login.
  LOGIN_IP varchar(39)       not null,
  LOGIN_TIME timestamp       not null,
  LOGOUT_IP varchar(39),
  LOGOUT_TIME timestamp,
  constraint DW1_LOGINS_SNO__P primary key (SNO), -- SHOULD incl TENANT?
  constraint DW1_LOGINS_TNT__R__TENANTS  -- ix DW1_LOGINS_TNT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_LOGINS__R__LOGINS  -- ix DW1_LOGINS_PREVL
      foreign key (PREV_LOGIN)
      references DW1_LOGINS(SNO) deferrable,
  constraint DW1_LOGINS_IDTYPE__C
      check (ID_TYPE in ('Simple', 'OpenID')),
  constraint DW1_LOGINS_SNO_NOT_0__C check (SNO <> '0')
);

create index DW1_LOGINS_TNT on DW1_LOGINS(TENANT);
create index DW1_LOGINS_PREVL on DW1_LOGINS(PREV_LOGIN);

create sequence DW1_LOGINS_SNO start with 10;

-- For usage by both _IDS_SIMPLE and _OPENID, so a given identity-SNO
-- is found in only one of _SIMPLE and _OPENID.
create sequence DW1_IDS_SNO start with 10;

-- Simple login identities (no password needed).
-- (Could rename to DW1_USERS_UNAU? (unauthenticated users)
-- Would that make sense? since a "dummy" user is created anyway (read on).)
-- When loaded from database, a dummy User is created, with its id
-- set to -SNO (i.e. "-" + SNO). Users with ids starting with "-"
-- are thus unauthenticated users (and don't exist in DW1_USERS).
-- If value absent, '-' is inserted -- but not '', since Oracle converts
-- '' to null, but I think it's easier to write SQL queries if I don't
-- have to take all possible combinations of null values into account.
create table DW1_IDS_SIMPLE(
  SNO varchar(32)         not null,  -- COULD rename to GUID
  NAME varchar(100)       not null,
  -- COULD require like '%@%.%' and update all existing data ... hmm.
  EMAIL varchar(100)      not null,
  LOCATION varchar(100)   not null,
  WEBSITE varchar(100)    not null,
  constraint DW1_IDSSIMPLE_SNO__P primary key (SNO),
  constraint DW1_IDSSIMPLE__U unique (NAME, EMAIL, LOCATION, WEBSITE),
  constraint DW1_IDSSIMPLE_SNO_NOT_0__C check (SNO <> '0')
);

-- (Uses sequence number from DW1_IDS_SNO.)

-- (Could rename to DW1_USERS_UNAU_EMAIL?)
create table DW1_IDS_SIMPLE_EMAIL(  -- abbreviated IDSMPLEML
  TENANT varchar(32) not null,
  -- The user (session) that added this row.
  LOGIN varchar(32),
  CTIME timestamp not null,
  -- C = current,  O = old, kept for auditing purposes.
  VERSION char(1) not null,
  -- We might actually attempt to send an email to this address,
  -- if EMAIL_NOTFS is set to 'R'eceive.
  EMAIL varchar(100) not null,
  -- Email notifications: R = receive, N = do Not receive, F = forbid forever
  EMAIL_NOTFS varchar(1) not null,
  -- Is this PK unnecessary?
  constraint DW1_IDSMPLEML__P primary key (TENANT, EMAIL, CTIME),
  constraint DW1_IDSMPLEML__R__LOGINS  -- ix DW1_IDSMPLEML_LOGIN
      foreign key (LOGIN)  -- SHOULD include TENANT?
      references DW1_LOGINS(SNO),
  constraint DW1_IDSMPLEML_EMAIL__C check (EMAIL like '%@%.%'),
  constraint DW1_IDSMPLEML_VERSION__C check (VERSION in ('C', 'O')),
  constraint DW1_IDSMPLEML_NOTFS__C check (EMAIL_NOTFS in ('R', 'N', 'F'))
);

-- For each email, there's only one current setting.
create unique index DW1_IDSMPLEML_VERSION__U
  on DW1_IDS_SIMPLE_EMAIL (TENANT, EMAIL, VERSION)
  where VERSION = 'C';

-- Foregin key index.
create index DW1_IDSMPLEML_LOGIN on DW1_IDS_SIMPLE_EMAIL (LOGIN);


-- Could: create table DW1_IDS_SIMPLE_NAME, to rewrite inappropriate names,
-- e.g. rewrite to "F_ck" or "_ssh_l_".


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
-- COULD save Null instead of '-' if data absent, and add not = '' constraints.
-- (Would DW1_AU_OPENID (AUthentication via OpenID) be a better name?)

create table DW1_IDS_OPENID(
  SNO varchar(32)                not null,  -- COULD rename to GUID
  TENANT varchar(32)             not null,
  -- When an OpenID identity is created, a User is usually created too.
  -- It is stored in USR_ORIG. However, to allow many OpenID identities to
  -- use the same User (i.e. merge OpenID accounts, and perhaps Twitter,
  -- Facebok accounts), each _OPENID row can be remapped to another User.
  -- This is done via the USR row (which is = USR_ORIG if not remapped).
  -- (Not yet implemented though: currently USR = USR_ORIG always.)
  USR varchar(32)                not null,
  USR_ORIG varchar(32)           not null,
  OID_CLAIMED_ID varchar(500)    not null, -- Google's ID hashes 200-300 long
  OID_OP_LOCAL_ID varchar(500)   not null,
  OID_REALM varchar(100)         not null,
  OID_ENDPOINT varchar(100)      not null,
  -- The version is a URL, e.g. "http://specs.openid.net/auth/2.0/server".
  OID_VERSION varchar(100)       not null,
  FIRST_NAME varchar(100)        not null,
  EMAIL varchar(100)             not null,
  COUNTRY varchar(100)           not null,
  constraint DW1_IDSOID_SNO__P primary key (SNO),
  constraint DW1_IDSOID_TNT_OID__U
      unique (TENANT, OID_CLAIMED_ID),
  constraint DW1_IDSOID_USR_TNT__R__USERS  -- ix DW1_IDSOID_TNT_USR
      foreign key (TENANT, USR)
      references DW1_USERS(TENANT, SNO) deferrable,
  constraint DW1_IDSOID_SNO_NOT_0__C check (SNO <> '0')
);

create index DW1_IDSOID_TNT_USR on DW1_IDS_OPENID(TENANT, USR);
create index DW1_IDSOID_EMAIL on DW1_IDS_OPENID(EMAIL);

-- (Uses sequence nunmber from DW1_IDS_SNO.)


-- Later: DW1_USER_ACTIONS?


----- Pages

-- (How do we know who created the page? The user who created
-- the root post, its page-action-id is always "1".)
-- COULD remove? select ... from DW1_PAGE_ACTIONS where PAID = '1' instead
-- COULD really remove, use PAGE_PATHS instead and rename it to PAGES?
create table DW1_PAGES(
  SNO varchar(32)       not null,   -- COULD remove, use only GUID
  TENANT varchar(32)    not null,
  GUID varchar(32)      not null,
  constraint DW1_PAGES_SNO__P primary key (SNO),
  constraint DW1_PAGES__U unique (TENANT, GUID),
  constraint DW1_PAGES__R__TENANT  -- ix: primary key, well it SHOULD incl TNT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_PAGES_SNO_NOT_0__C check (SNO <> '0')
);

create sequence DW1_PAGES_SNO start with 10;


----- Actions

-- Contains all posts, edits, ratings etc, everything that's needed to
-- render a discussion.
create table DW1_PAGE_ACTIONS(   -- abbreviated PGAS (PACTIONS deprectd abbrv.)
  PAGE varchar(32)     not null,
  PAID varchar(32)     not null,  -- page action id
  LOGIN varchar(32)    not null,
  TIME timestamp       not null,
  TYPE varchar(20)     not null,
  RELPA varchar(32)    not null,  -- related page action
  -- STATUS char(1)    not null,  -- check in 'S', 'A' -- suggestion/applied
  TEXT text,
  MARKUP varchar(30),
  WHEERE varchar(150),
  NEW_IP varchar(39), -- null, unless differs from DW1_LOGINS.START_IP
  constraint DW1_PACTIONS_PAGE_PAID__P primary key (PAGE, PAID),
  constraint DW1_PACTIONS__R__LOGINS  -- ix DW1_PACTIONS_LOGIN
      foreign key (LOGIN)
      references DW1_LOGINS (SNO) deferrable,
  constraint DW1_PACTIONS__R__PAGES  -- ix DW1_PACTIONS_PAGE_PAID__P
      foreign key (PAGE)
      references DW1_PAGES (SNO) deferrable,
  constraint DW1_PACTIONS__R__PACTIONS
      foreign key (PAGE, RELPA) -- no index: no deletes/upds in parent table
                         -- and no joins (loading whole page at once instead)
      references DW1_PAGE_ACTIONS (PAGE, PAID) deferrable,
  constraint DW1_PGAS_TYPE__C check (TYPE in (
        'Post', 'Title', 'Publ', 'Meta', 'Edit',
        'EditApp', -- SHOULD replace w Publd?
        'Rating',
        -- 'Reason' -- no, only "needed" for Edit - but can use Title instead.
        -- (Other post types can have their reason inlined, TEXT isn't used
        -- for those other types.)
        'DelPost', 'DelTree',
        'FlagSpam', 'FlagIllegal', 'FlagCopyVio', 'FlagOther')),
  -- There must be no action with id 0; let 0 mean nothing.
  constraint DW1_PACTIONS_PAID_NOT_0__C
      check (PAID <> '0'),
  -- The root post has id 1 and it must be its own parent.
  constraint DW1_PACTIONS_ROOT_IS_1__C
      check (RELPA = case when PAID = '1' then '1' else RELPA end),
  -- The root post must be a 'Post'.
  constraint DW1_PACTIONS_ROOT_IS_POST__C
      check (TYPE = case when PAID = '1' then 'Post' else TYPE end)
);
  -- Cannot create an index organized table -- not available in Postgre SQL.

-- todo prod: (done dev & test) --------
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_PAID__C_NE
  check (trim(PAID) <> '');
update DW1_PAGE_ACTIONS set MARKUP = null where TYPE <> 'Post';
update DW1_PAGE_ACTIONS set MARKUP = 'dmd0' where TYPE = 'Post';
update DW1_PAGE_ACTIONS set TEXT = null where TEXT = '';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TEXT__C_NE
  check (trim(TEXT) <> '');
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_MARKUP__C_NE
  check (trim(MARKUP) <> '');
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_POST_MARKUP__C_NN
  check (
    case TYPE
      when 'Post' then (MARKUP is not null)
      else true
    end
  );
update DW1_PAGE_ACTIONS set WHEERE = null where WHEERE = '';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_WHERE__C_NE
  check (trim(WHEERE) <> '');
update DW1_PAGE_ACTIONS set NEW_IP = null where NEW_IP = '';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_NEWIP__C_NE
  check (trim(NEW_IP) <> '');
-- todo prod END ---------------------------


-- Needs an index on LOGIN: it's an FK to DW1_LOINGS, whose END_IP/TIME is
-- updated at the end of the session.
create index DW1_PACTIONS_LOGIN on DW1_PAGE_ACTIONS(LOGIN);

-- todo prod:  (done dev, test)
alter table DW1_PAGE_ACTIONS drop constraint DW1_PACTIONS_TYPE__C;
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TYPE__C check (TYPE in (
        'Post', 'Title', 'Publ', 'Meta', 'Edit',
        'EditApp', -- SHOULD replace w Publd?
        'Rating',
        -- 'Reason' -- no, only "needed" for Edit - but can use Title instead.
        -- (Other post types can have their reason inlined, TEXT isn't used
        -- for those other types.)
        'DelPost', 'DelTree',
        'FlagSpam', 'FlagIllegal', 'FlagCopyVio', 'FlagOther'));

create table DW1_PAGE_RATINGS(  -- abbreviated PGRTNGS? PGRS? PRATINGS deprctd.
  PAGE varchar(32) not null,
  PAID varchar(32) not null, -- page action id
  TAG varchar(30) not null,
  constraint DW1_PRATINGS__P primary key (PAGE, PAID, TAG),
  constraint DW1_PRATINGS__R__PACTIONS  -- ix DW1_PRATINGS__P
      foreign key (PAGE, PAID)
      references DW1_PAGE_ACTIONS(PAGE, PAID) deferrable
);


----- Emails and Inbox

create table DW1_EMAILS_OUT(  -- abbreviated EMLOT
  TENANT varchar(32) not null,
  GUID varchar(32) not null,
  SENT_TO varchar(100) not null,  -- only one recipient, for now
  SENT_ON timestamp not null,
  SUBJECT varchar(200) not null,
  HTML varchar(2000) not null,
  -- E.g. Amazon SES assigns their own guid to each email. Their API returns
  -- the guid when the email is sent (it's not available until then).
  -- If an email bounces, you then look up the provider's email guid
  -- to find out which email bounced, and which addresses.
  PROVIDER_EMAIL_GUID varchar(100), -- no default
  -- B/R/C/O: bounce, rejection, complaint, other.
  FAILURE_TYPE varchar(1) default null,
  -- E.g. a bounce or rejection message.
  FAILURE_TEXT varchar(2000) default null,
  FAILURE_TIME timestamp default null,
  constraint DW1_EMLOT__R__TNTS  -- ix DW1_EMLOT__P
      foreign key (TENANT)
      references DW1_TENANTS (ID),
  constraint DW1_EMLOT__P primary key (TENANT, GUID),
  constraint DW1_EMLOT_FAILTYPE__C check (
      FAILURE_TYPE in ('B', 'R', 'C', 'O')),
  constraint DW1_EMLOT_FAILTEXT__C check (
      FAILURE_TYPE is null = FAILURE_TYPE is null),
  constraint DW1_EMLOT_FAILTIME check (
      FAILURE_TIME is null = FAILURE_TYPE is null)
);

-- Page action notifications for a role (then ROLE is non-null),
-- or an unauthenticated user (then ID_SIMPLE is non-null).
create table DW1_INBOX_PAGE_ACTIONS(   -- abbreviated IBXPGA
  TENANT varchar(32) not null,
  ID_SIMPLE varchar(32),
  ROLE varchar(32),
  PAGE varchar(32) not null,
  -- The source page action id is the action that generated this inbox item.
  -- The target page action id is the action of interest to the user.
  -- For example, if someone replies to your comment,
  -- and the reply is published instantly,
  -- then, if the reply has id X, SOURCE_PGA = TARGET_PGA = X.
  -- If however the reply is not published until after it's been reviewed,
  -- then, the reply has id X and the review action id has id Y,
  -- SOURCE_PGA = Y (i.e. the review) and TARGET_PGA = X (i.e. the reply).
  TARGET_PGA varchar(32) not null,
  SOURCE_PGA varchar(32) not null,
  CTIME timestamp not null,
  -- New or Old. New means: Attempt to notify the user, perhaps send email.
  -- Old means: The user has been notified. Okay to delete, or keep for
  -- auditing purposes.
  STATUS varchar(1) default 'N' not null,
  -- Email notifications
  EMAIL_SENT varchar(32) default null, -- DW1_EMAILS_OUT.GUID
  EMAIL_LINK_CLICKED timestamp default null,
  -- WEB_LINK_SHOWN timestamp,
  -- WEB_LINK_CLICKED timestamp,
  -- (There's no primary key -- there're 2 unique indexes instead and a
  -- check constraint, DW1_IBXPGA_IDSMPL_ROLE__C, that ensures one of those
  -- unique indexes is active.)
  constraint DW1_IBXPGA_SRCPGA__R__PGAS
      foreign key (PAGE, SOURCE_PGA) -- no index: no deletes/upds in prnt tbl
      references DW1_PAGE_ACTIONS (PAGE, PAID) deferrable,
  constraint DW1_IBXPGA_TGTPGA__R__PGAS
      foreign key (PAGE, TARGET_PGA) -- no index: no deletes/upds in prnt tbl
      references DW1_PAGE_ACTIONS (PAGE, PAID) deferrable,
  constraint DW1_IBXPGA__R__RLS  -- ix DW1_IBXPGA_ROLE_CTIME
      foreign key (TENANT, ROLE)
      references DW1_USERS (TENANT, SNO) deferrable,
  -- Ensure exactly one of ROLE and ID_SIMPLE is specified.
  constraint DW1_IBXPGA_IDSMPL_ROLE__C check (
      (ROLE is null) <> (ID_SIMPLE is null)),
  constraint DW1_IBXPGA_STATUS__C check (STATUS in ('N', 'O')),
  constraint DW1_IBXPGA__R__EMLOT  -- ix DW1_IBXPGA_EMAILSENT
      foreign key (TENANT, EMAIL_SENT)
      references DW1_EMAILS_OUT (TENANT, GUID),
  constraint  DW1_IBXPGA_EMAILCLKD__C check (
      case
        when (EMAIL_LINK_CLICKED is null) then true
        else EMAIL_SENT is not null
      end)
);

-- Add two unique indexes that ensure each SOURCE_PGA results in only
-- one notification to each user.
-- Note that (TENANT, ROLE/ID_SIMPLE, PAGE, TARGET_PGA) need not be unique,
-- for example, the same post might be edited many times, resulting
-- in events with that post being the target (but with different SOURCE_PGA).
create unique index DW1_IBXPGA_TNT_RL_PG_SRC__U
    on DW1_INBOX_PAGE_ACTIONS (TENANT, ROLE, PAGE, SOURCE_PGA)
    where ROLE is not null;
create unique index DW1_IBXPGA_T_IDSMPL_PG_SRC__U
    on DW1_INBOX_PAGE_ACTIONS (TENANT, ID_SIMPLE, PAGE, SOURCE_PGA)
    where ID_SIMPLE is not null;

create index DW1_IBXPGA_TNT_ROLE_CTIME
    on DW1_INBOX_PAGE_ACTIONS (TENANT, ROLE, CTIME);
create index DW1_IBXPGA_TNT_IDSMPL_CTIME
    on DW1_INBOX_PAGE_ACTIONS (TENANT, ID_SIMPLE, CTIME);
create index DW1_IBXPGA_TNT_STATUS_CTIME
    on DW1_INBOX_PAGE_ACTIONS (TENANT, STATUS, CTIME);
create index DW1_IBXPGA_TNT_EMAILSENT
    on DW1_INBOX_PAGE_ACTIONS (TENANT, EMAIL_SENT);


----- Paths (move to Pages section above?)

-- TODO create in prod (dev, test done) and copy values from DW1_PATHS.
create table DW1_PAGE_PATHS(  -- abbreviated PGPTHS
  TENANT varchar(32) not null,
  PARENT_FOLDER varchar(100) not null,
  PAGE_ID varchar(32) not null,
  SHOW_ID varchar(1) not null,
  PAGE_SLUG varchar(100) not null,
  -- The page status, see Debiki for Developers #9vG5I.
  -- 'D'raft, 'P'ublished, 'X' deleted, 'E'rased.
  PAGE_STATUS varchar(1) not null,
  -- Should be updated whenever the page is renamed.
  CACHED_TITLE varchar(100) default null,
  -- When the page body was published (draft versions don't count).
  CACHED_PUBL_TIME timestamp default null,
  -- When the page was last modified in a significant way. Of interest
  -- to e.g. Atom feeds.
  CACHED_SGFNT_MTIME timestamp default null,
  constraint DW1_PGPTHS_TNT_PGID__P primary key (TENANT, PAGE_ID),
  constraint DW1_PGPTHS_TNT_PGID__R__PAGES  -- ix DW1_PGPTHS_TNT_PAGE__P
      foreign key (TENANT, PAGE_ID)
      references DW1_PAGES (TENANT, GUID) deferrable,
  -- Ensure the folder path contains no page ID mark; '-' means an id follows.
  constraint DW1_PGPTHS_FOLDER__C_DASH check (PARENT_FOLDER not like '%/-%'),
  constraint DW1_PGPTHS_FOLDER__C_START check (PARENT_FOLDER like '/%'),
  constraint DW1_PGPTHS_SHOWID__C_IN check (SHOW_ID in ('T', 'F')),
  constraint DW1_PGPTHS_SLUG__C_NE check (trim(PAGE_SLUG) <> ''),
  constraint DW1_PGPTHS_PGSTS__C_IN check (PAGE_STATUS in('D', 'P', 'X', 'E')),
  constraint DW1_PGPTHS_CACHEDTITLE__C_NE check (trim(CACHED_TITLE) <> ''),
  constraint DW1_PGPTHS_MTIME_PUBLTIME__C check (
      CACHED_SGFNT_MTIME >= CACHED_PUBL_TIME)
);

-- TODO: Test case: no 2 pages with same path
-- Create an index that ensures (tenant, folder, page-slug) is unique,
-- if the page guid is not included in the path to the page.
-- That is, if the path is like:  /some/folder/page-slug  (*no* guid in path)
-- rather than:  /some/folder/-<guid>-page-slug  (guid included in path)
-- then ensure there's no other page with the same /some/folder/page-slug.
create unique index DW1_PGPTHS__U
on DW1_PAGE_PATHS(TENANT, PARENT_FOLDER, PAGE_SLUG)
where SHOW_ID = 'F';

-- Also create an index that covers *all* pages (even those without the ID
-- shown before the page slug).
create index DW1_PGPTHS_ALL
on DW1_PAGE_PATHS(TENANT, PARENT_FOLDER, PAGE_SLUG, PAGE_ID);

/* TODO: prod (dev, test: done)
insert into DW1_PAGE_PATHS (
  TENANT, PARENT_FOLDER, PAGE_ID,
  PAGE_SLUG,
  SHOW_ID,
  PAGE_STATUS, CACHED_TITLE, CACHED_PUBL_TIME, CACHED_SGFNT_MTIME)
select
  TENANT, FOLDER, PAGE_GUID,
  case PAGE_NAME when ' ' then '-' else PAGE_NAME end,
  GUID_IN_PATH,
  'D', null, null, null
from DW1_PATHS;
*/


