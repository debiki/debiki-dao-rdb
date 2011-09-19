-- Copying/converting data from DW0 tables to DW1 tables,
-- My SQL Developer connection name:
--   dw0ccsedbpv11-alf debiki_prod 1521
-- user: debiki_prod
-- host: 127.0.0.1
-- port: 51521
-- service name: xe
-- SSH tunnel:
--   ssh -L 51521:dw0ccsedbpv11-alf:1521 -p59505 kajm@dw0ccsedbpv11alf


insert into DW1_PAGES (TENANT, SNO, GUID)
select '2' TENANT, DW1_PAGES_SNO.nextval SNO, GUID from DW0_PAGES;

--

insert into DW1_PATHS (TENANT, FOLDER, PAGE_GUID, PAGE_NAME, GUID_IN_PATH)
select
  '2' TENANT, PARENT FOLDER, PAGE_GUID, PAGE_NAME,
    case when GUID_IN_PATH = ' ' then 'F' else 'T' end GUID_IN_PATH
from DW0_PAGEPATHS;

--

execute dbms_errlog.create_error_log('DW1_IDS_SIMPLE', 'ERRLOG');

insert into DW1_IDS_SIMPLE(SNO, NAME, EMAIL, LOCATION, WEBSITE)
select DW1_IDS_SNO.nextval,
  NVL(REGEXP_SUBSTR(WHO,'[^|]+', 1, 2), '-') NAME,
  NVL(REGEXP_SUBSTR(WHO,'[^|]+', 1, 3), '-') EMAIL,
  '-' LOCATION,
  NVL(REGEXP_SUBSTR(WHO,'[^|]+', 1, 4), '-') WEBSITE
from DW0_ACTIONS
log errors into ERRLOG reject limit 99999;

--

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
  
--


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

--


insert into DW1_PAGE_RATINGS(PAGE, PAID, TAG)
select a1.PAGE, a1.PAID, r0.TAG
from DW0_RATINGS r0, DW0_ACTIONS a0, DW1_PAGE_ACTIONS a1, DW1_PAGES p1
where a0.SNO = r0.ACTION_SNO
  and p1.GUID = a0.PAGE
  and a1.PAGE = p1.SNO
  and a1.PAID = cast(a0.ID as varchar(30));


commit;


-- RESULT from running above script, today 2011-09-19:
--
-- 12 rows inserted.
-- 12 rows inserted.
-- anonymous block completed
-- 17 rows inserted.
-- 135 rows inserted.
-- 135 rows inserted.
-- 22 rows inserted.
-- commited.

