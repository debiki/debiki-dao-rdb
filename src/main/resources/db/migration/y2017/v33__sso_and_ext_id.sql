
alter table settings3 add column sso_enabled boolean;
alter table settings3 add column sso_secret varchar;
alter table settings3 add column sso_login_url varchar;
alter table settings3 add column sso_logout_url varchar;
alter table settings3 add column embedded_only_hide_site varchar;

-- Otherwise SSO becomes too complicated, for now.
alter table settings3 add constraint settings_c_sso_restrictions check (
  not sso_enabled or (
    (require_verified_email is null or require_verified_email)
    and (may_compose_before_signup is null or not may_compose_before_signup)
    and (may_login_before_email_verified is null or not may_login_before_email_verified)
    and (allow_guest_login is null or not allow_guest_login)));

alter table settings3 add constraint settings_c_for_site_ssoenabled check (
  (category_id is null and page_id is null) or sso_enabled is null);

alter table settings3 add constraint settings_c_for_site_ssosecret check (
  (category_id is null and page_id is null) or sso_secret is null);

alter table settings3 add constraint settings_c_for_site_ssologinurl check (
  (category_id is null and page_id is null) or sso_login_url is null);

alter table settings3 add constraint settings_c_for_site_ssologouturl check (
  (category_id is null and page_id is null) or sso_logout_url is null);

alter table settings3 add constraint settings_c_for_site_embonlyhidesite check (
  (category_id is null and page_id is null) or embedded_only_hide_site is null);

alter table identities3 add column ext_id varchar;
alter table identities3 add column ext_is_admin boolean;
alter table identities3 add column ext_is_moderator boolean;
alter table identities3 add column username varchar;
alter table identities3 add column about_user varchar;
alter table identities3 add column gender int;

