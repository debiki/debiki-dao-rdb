-- Schema: debiki_dev_0_0_2
-- DROP SCHEMA debiki_dev_0_0_2;
CREATE SCHEMA debiki_dev_0_0_2
  AUTHORIZATION debiki_dev_0_0_2;


----- Reset schema

drop table DW0_VERSION;
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

----- Version

create table DW0_VERSION(
  VERSION varchar(100) constraint DW0_VERSION_VERSION__N not null
);

----- Tenants

create table DW1_TENANTS(
  ID varchar(10) not null,
  NAME varchar(100) not null,
  CTIME timestamp default now() not null,
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
  TENANT varchar(10) not null,
  HOST varchar(50) not null,
  CANONICAL varchar(10),
  -- HTTPS values: `Required': http redirects to https, `Allowed': https
  -- won't redirect to http, but includes a <link rel=canonical>.
  -- `No': https times out or redirects to http.
  HTTPS varchar(10),
  CTIME timestamp default now() not null,
  MTIME timestamp default now() not null,
  constraint DW1_TNTHSTS__R__TENANTS
      foreign key (TENANT)
      references DW1_TENANTS(ID),
  constraint DW1_TNTHSTS_HOST__U unique (HOST),
  constraint DW1_TNTHSTS_CNCL__C check (CANONICAL in ('T', 'F')),
  constraint DW1_TNTHSTS_HTTPS__C check (HTTPS in ('Required', 'Allowed', 'No'))
);

-- Make sure there's only one canonical host per tenant.
create unique index DW1_TNTHSTS_TENANT_CNCL__U on DW1_TENANT_HOSTS(TENANT)
where CANONICAL = 'T';

-- Create a Local tenant that redirects 127.0.0.1 to `localhost'.
insert into DW1_TENANTS(ID, NAME) values ('1', 'Local');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, 'localhost2:8080', 'T', 'No');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, '127.0.0.1:8080', 'F', 'No');
commit;


----- Users

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
  TENANT varchar(10)          not null,
  SNO bigint                  not null,
  DISPLAY_NAME varchar(100),  -- currently empty, always (2011-09-17)
  EMAIL varchar(100),
  COUNTRY varchar(100),
  WEBSITE varchar(100),
  SUPERADMIN varchar(1),
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
  SNO bigint                 not null,
  TENANT varchar(10)         not null,
  PREV_LOGIN bigint,
  -- COULD replace ID_TYPE/_SNO with: ID_SIMPLE, ID_OPENID, ID_TWITTER, etc,
  -- require that exactly one be non-NULL, and create foreign keys to the ID
  -- tables. That would have avoided a few bugs! and more future bugs too!?
  ID_TYPE varchar(10)        not null,
  ID_SNO bigint              not null,
  LOGIN_IP varchar(39)       not null,
  LOGIN_TIME timestamp       not null,
  LOGOUT_IP varchar(39),
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
);

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
  SNO bigint              not null,
  NAME varchar(100)       not null,
  EMAIL varchar(100)      not null,
  LOCATION varchar(100)   not null,
  WEBSITE varchar(100)    not null,
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
  SNO bigint                     not null,
  TENANT varchar(10)             not null,
  -- When an OpenID identity is created, a User is usually created too.
  -- It is stored in USR_ORIG. However, to allow many OpenID identities to
  -- use the same User (i.e. merge OpenID accounts, and perhaps Twitter,
  -- Facebok accounts), each _OPENID row can be remapped to another User.
  -- This is done via the USR row (which is = USR_ORIG if not remapped).
  -- (Not yet implemented though: currently USR = USR_ORIG always.)
  USR bigint                     not null,
  USR_ORIG bigint                not null,
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
  constraint DW1_IDSOID_USR_TNT__R__USERS
      foreign key (TENANT, USR)
      references DW1_USERS(TENANT, SNO) deferrable,
  constraint DW1_IDSOID_SNO_NOT_0__C check (SNO <> 0)
);

create index DW1_IDSOID_TNT_USR on DW1_IDS_OPENID(TENANT, USR);
create index DW1_IDSOID_EMAIL on DW1_IDS_OPENID(EMAIL);

-- (Uses sequence nunmber from DW1_IDS_SNO.)


-- Later: DW1_USER_ACTIONS?


----- Pages

-- (How do we know who created the page? The user who created
-- the root post, its page-action-id is always "1".)
create table DW1_PAGES(
  SNO bigint            not null,
  TENANT varchar(10)    not null,
  GUID varchar(50)      not null,
  constraint DW1_PAGES_SNO__P primary key (SNO),
  constraint DW1_PAGES__U unique (TENANT, GUID),
  constraint DW1_PAGES__R__TENANT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_PAGES_SNO_NOT_0__C check (SNO <> 0)
);

create sequence DW1_PAGES_SNO start with 10;


----- Actions

-- Contains all posts, edits, ratings etc, everything that's needed to
-- render a discussion.
create table DW1_PAGE_ACTIONS(
  PAGE bigint          not null,
  PAID varchar(30)     not null,  -- page action id
  LOGIN bigint         not null,
  TIME timestamp       not null,
  TYPE varchar(10)     not null,
  RELPA varchar(30)    not null,  -- related page action
  -- STATUS char(1)    not null,  -- check in 'S', 'A' -- suggestion/applied
  TEXT text,
  MARKUP varchar(30),
  WHEERE varchar(150),
  NEW_IP varchar(39), -- null, unless differs from DW1_LOGINS.START_IP
  DESCR varchar(1001), -- remove? use a new Post instead, to descr this action
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
);
  -- Cannot create an index organized table -- not available in Postgre SQL.

-- Needs an index on LOGIN: it's an FK to DW1_LOINGS, whose END_IP/TIME is
-- updated at the end of the session.
create index DW1_PACTIONS_LOGIN on DW1_PAGE_ACTIONS(LOGIN);

create table DW1_PAGE_RATINGS(
  PAGE bigint not null,
  PAID varchar(30) not null, -- page action id
  TAG varchar(30) not null,
  constraint DW1_PRATINGS__P primary key (PAGE, PAID, TAG),
  constraint DW1_PRATINGS__R__PACTIONS
      foreign key (PAGE, PAID)
      references DW1_PAGE_ACTIONS(PAGE, PAID) deferrable
);

----- Paths (move to Pages section above?)

create table DW1_PATHS(
  TENANT varchar(10) not null,
  FOLDER varchar(100) not null,
  PAGE_GUID varchar(30) not null,
  PAGE_NAME varchar(100) not null,
  GUID_IN_PATH varchar(1) not null,
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
create unique index DW1_PATHS__U on DW1_PATHS(TENANT, FOLDER, PAGE_NAME, PAGE_GUID)
where GUID_IN_PATH = 'T';


-- Also create an index that covers *all* pages (even those without GUID in path).
create index DW1_PATHS_ALL on DW1_PATHS(TENANT, FOLDER, PAGE_NAME, PAGE_GUID);



----- Misc changes

-- Add flags and deletions and meta to DW1 tables.
-- Let TYPE be 20 chars not 10, so "FlagCopyVio" fits.
alter table DW1_PAGE_ACTIONS alter TYPE type varchar(20);
-- Add new action types.
alter table DW1_PAGE_ACTIONS drop constraint DW1_PACTIONS_TYPE__C;
alter table DW1_PAGE_ACTIONS add constraint DW1_PACTIONS_TYPE__C
check (TYPE in (
    'Post', 'Meta', 'Edit', 'EditApp', 'Rating',
    'DelPost', 'DelTree',
    'FlagSpam', 'FlagIllegal', 'FlagCopyVio', 'FlagOther'));

-- Drop unneeded column.
alter table DW1_PAGE_ACTIONS drop column DESCR;

