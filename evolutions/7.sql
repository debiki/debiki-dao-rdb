# This evolution is for embedded sites:
# - Allows websites without names (they're looked up by id instead)
# - Adds EMBEDDING_SITE_ADDRESS column



# --- !Ups

alter table DW1_TENANTS alter column NAME drop not null;

alter table DW1_TENANTS drop constraint DW1_TNT_NAME__C_NE;
alter table DW1_TENANTS add constraint DW1_TNT_NAME__C_NE check (NAME is null or trim(NAME) <> '');

alter table DW1_TENANTS add column EMBEDDING_SITE_ADDRESS varchar;
alter table DW1_TENANTS add constraint DW1_TNT_EMBSITEADDR__C_LEN
    check(length(EMBEDDING_SITE_ADDRESS) < 100);


# --- !Downs

alter table DW1_TENANTS drop column EMBEDDING_SITE_ADDRESS;

alter table DW1_TENANTS drop constraint DW1_TNT_NAME__C_NE;
alter table DW1_TENANTS add constraint DW1_TNT_NAME__C_NE check (trim(NAME) <> '');

alter table DW1_TENANTS alter column NAME set not null;

