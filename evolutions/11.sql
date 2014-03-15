-- This evolution adds a settings table.


# --- !Ups


create table DW1_SETTINGS(
  TENANT_ID varchar,
  TYPE varchar,
  PAGE_ID varchar,
  ROLE_ID varchar,
  NAME varchar not null,
  TEXT_VALUE varchar,
  LONG_VALUE bigint,
  DOUBLE_VALUE double precision,
  constraint DW1_STNGS_TYPE__C_FKS check (
    case TYPE
      when 'WholeSite'   then ROLE_ID is null and PAGE_ID is null
      when 'PageTree'    then ROLE_ID is null and PAGE_ID is not null
      when 'SinglePage'  then ROLE_ID is null and PAGE_ID is not null
      when 'Role'        then ROLE_ID is not null and PAGE_ID is null
      else false
    end),
  constraint DW1_STNGS__C_VALUE check (
    (TEXT_VALUE is not null and LONG_VALUE is null and DOUBLE_VALUE is null) or
    (TEXT_VALUE is null and LONG_VALUE is not null and DOUBLE_VALUE is null) or
    (TEXT_VALUE is null and LONG_VALUE is null and DOUBLE_VALUE is not null)),
  constraint DW1_STNGS_TNT_TYPE_PAGE_NAME__U unique (TENANT_ID, TYPE, PAGE_ID, NAME),
  constraint DW1_STNGS_TNT_TYPE_ROLE_NAME__U unique (TENANT_ID, TYPE, ROLE_ID, NAME),
  constraint DW1_STNGS_NAME__C check ((length(NAME) between 1 and 50) and (trim(NAME) = NAME)),
  constraint DW1_STNGS_TEXTVALUE__C_LEN check (length(TEXT_VALUE) < 10*1000),
  constraint DW1_STNGS_PAGEID__R__PAGES foreign key (TENANT_ID, PAGE_ID) references DW1_PAGES(TENANT, GUID),
  constraint DW1_STNGS_ROLEID__R__ROLES foreign key (TENANT_ID, ROLE_ID) references DW1_USERS(TENANT, SNO)
);



# --- !Downs


drop table DW1_SETTINGS;

