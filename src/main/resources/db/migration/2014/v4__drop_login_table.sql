
alter table DW1_PAGE_ACTIONS drop column LOGIN;
-- Also dropped constraint DW1_PGAS_LOGIN_GUEST_ROLE__C.

alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_GUEST_ROLE__C check (
    (GUEST_ID is null and ROLE_ID is null)
    or
    ((GUEST_ID is null) <> (ROLE_ID is null)));


alter table DW1_IDS_SIMPLE_EMAIL drop column LOGIN;

