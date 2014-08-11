
alter table DW1_PAGE_ACTIONS drop column LOGIN;
-- Also dropped constraint DW1_PGAS_LOGIN_GUEST_ROLE__C.

alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_GUEST_ROLE__C check (
    (GUEST_ID is null and ROLE_ID is null)
    or
    ((GUEST_ID is null) <> (ROLE_ID is null)));


alter table DW1_IDS_SIMPLE_EMAIL drop column LOGIN;
alter table DW1_QUOTAS drop column NUM_LOGINS;
alter table DW1_TENANTS drop column CREATOR_LOGIN_ID;

drop table DW1_LOGINS;
drop sequence DW1_LOGINS_SNO;


alter table DW1_USERS add column USERNAME varchar;
-- Scala code currently requires 3 - 20 chars, but be a bit lax here in the database
-- so I don't have to add an evolution just to change this.
alter table DW1_USERS add constraint DW1_USERS_USERNAME__C_LEN check (length(trim(USERNAME)) >= 2);
alter table DW1_USERS add constraint DW1_USERS_USERNAME__C_LEN2 check (length(USERNAME) < 40);

