
alter table settings3 add column feature_flags varchar;

alter table settings3 add column enable_sso boolean;
alter table settings3 add column debug_sso boolean;
alter table settings3 add column sso_url varchar;
alter table settings3 add column sso_not_approved_url varchar;

