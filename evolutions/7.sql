# This evolution is for embedded sites:
# - Allows websites without names (they're looked up by id instead)
# - Adds EMBEDDING_SITE_URL column



# --- !Ups


-- Allow null names, add whitespace test

alter table DW1_TENANTS alter column NAME drop not null;

alter table DW1_TENANTS drop constraint DW1_TNT_NAME__C_NE;

alter table DW1_TENANTS add constraint DW1_TNT_NAME__C_LEN check (
    length(NAME) between 1 and 100);

alter table DW1_TENANTS add constraint DW1_TNT_NAME__C_TRIM check (
    trim(NAME) = NAME);


-- Add column

alter table DW1_TENANTS add column EMBEDDING_SITE_URL varchar;

alter table DW1_TENANTS add constraint DW1_TNT_EMBSITEURL__C_LEN check (
    length(EMBEDDING_SITE_URL) between 1 and 100);

alter table DW1_TENANTS add constraint DW1_TNT_EMBSITEURL__C_TRIM check (
    trim(EMBEDDING_SITE_URL) = EMBEDDING_SITE_URL);


-- A site that isn't a real sites with a name, and also isn't embedded, isn't allowed.
alter table DW1_TENANTS add constraint DW1_TNT_NAME_EMBSITEURL__C check (
    NAME is not null or EMBEDDING_SITE_URL is not null);


# --- !Downs


alter table DW1_TENANTS drop column EMBEDDING_SITE_URL;

alter table DW1_TENANTS drop constraint DW1_TNT_NAME__C_NE;
alter table DW1_TENANTS add constraint DW1_TNT_NAME__C_NE check (trim(NAME) <> '');

update DW1_TENANTS set NAME = 'site-' || id where NAME is null; -- so can set not null
alter table DW1_TENANTS alter column NAME set not null;

