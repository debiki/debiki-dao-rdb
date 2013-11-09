# This evolution:
# - Adds email + password login columns to DW1_IDS_OPENID (which should be renamed
#   to DW1_IDENTITIES)
# - Adds TYPE and CREATED_AT columns to DW1_EMAILS_OUT


# --- !Ups

alter table DW1_IDS_OPENID add column PASSWORD_HASH varchar;

alter table DW1_IDS_OPENID add constraint DW1_IDS_PSWDHASH__C_LEN
  check (length(PASSWORD_HASH) < 100);

alter table DW1_IDS_OPENID add constraint DW1_IDS_PSWDHASH_EMAIL__C
  check (case
    when PASSWORD_HASH is not null
    then EMAIL is not null
     and OID_CLAIMED_ID is null
     and OID_OP_LOCAL_ID is null
     and OID_REALM is null
     and OID_ENDPOINT is null
     and OID_VERSION is null
     and FIRST_NAME is null
     and COUNTRY is null
  end);

alter table DW1_IDS_OPENID alter column OID_CLAIMED_ID drop not null;
alter table DW1_IDS_OPENID alter column OID_OP_LOCAL_ID drop not null;
alter table DW1_IDS_OPENID alter column OID_REALM drop not null;
alter table DW1_IDS_OPENID alter column OID_ENDPOINT drop not null;
alter table DW1_IDS_OPENID alter column OID_VERSION drop not null;
alter table DW1_IDS_OPENID alter column FIRST_NAME drop not null;
alter table DW1_IDS_OPENID alter column COUNTRY drop not null;

alter table DW1_IDS_OPENID add constraint DW1_IDSOID_OID__C_NN
  check (case
    when OID_CLAIMED_ID is not null
    then OID_OP_LOCAL_ID is not null
     and OID_REALM is not null
     and OID_ENDPOINT is not null
     and OID_VERSION is not null
     and PASSWORD_HASH is null
  end);


alter table DW1_EMAILS_OUT add column CREATED_AT timestamp;
update DW1_EMAILS_OUT set CREATED_AT = SENT_ON;
alter table DW1_EMAILS_OUT add constraint DW1_EMLOT_CREATED_SENT__C_LE
    check (CREATED_AT <= SENT_ON);


alter table DW1_EMAILS_OUT add column TYPE varchar;
update DW1_EMAILS_OUT set TYPE = 'Notf';
alter table DW1_EMAILS_OUT alter column TYPE set not null;
alter table DW1_EMAILS_OUT add constraint DW1_EMLOT_TYPE__C_IN
    check (TYPE in ('Notf', 'CrAc', 'RsPw'));


# --- !Downs

alter table DW1_EMAILS_OUT drop column TYPE;
alter table DW1_EMAILS_OUT drop column CREATED_AT;

alter table DW1_IDS_OPENID drop column PASSWORD_HASH;

alter table DW1_IDS_OPENID drop constraint DW1_IDSOID_OID__C_NN;

alter table DW1_IDS_OPENID alter column OID_CLAIMED_ID set not null;
alter table DW1_IDS_OPENID alter column OID_OP_LOCAL_ID set not null;
alter table DW1_IDS_OPENID alter column OID_REALM set not null;
alter table DW1_IDS_OPENID alter column OID_ENDPOINT set not null;
alter table DW1_IDS_OPENID alter column OID_VERSION set not null;
alter table DW1_IDS_OPENID alter column FIRST_NAME set not null;
alter table DW1_IDS_OPENID alter column COUNTRY set not null;
