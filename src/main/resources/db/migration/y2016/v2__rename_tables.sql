drop trigger if exists dw1_emails_summary on dw1_emails_out;
drop trigger if exists dw1_identities_summary on dw1_identities;
drop trigger if exists dw1_notfs_summary on dw1_notifications;
drop trigger if exists dw1_pages_summary on dw1_pages;
drop trigger if exists dw1_posts_read_summary on dw1_posts_read_stats;
drop trigger if exists dw1_role_page_settings_summary on dw1_role_page_settings;
drop trigger if exists dw1_roles_summary on dw1_users;
drop trigger if exists dw2_actions_summary on dw2_post_actions;
drop trigger if exists dw2_posts_summary on dw2_posts;
drop trigger if exists sum_post_revs_quota_3 on dw2_post_revisions;

drop function if exists dw1_emails_summary() cascade;
drop function if exists dw1_guests_summary() cascade;
drop function if exists dw1_identities_summary() cascade;
drop function if exists dw1_notfs_summary() cascade;
drop function if exists dw1_pages_summary() cascade;
drop function if exists dw1_posts_read_summary() cascade;
drop function if exists dw1_role_page_settings_summary() cascade;
drop function if exists dw1_roles_summary() cascade;
drop function if exists dw2_post_actions_summary() cascade;
drop function if exists dw2_posts_summary() cascade;
drop function if exists sum_post_revs_quota_3() cascade;


alter table dw1_emails_out rename to emails_out3;
alter table dw1_guest_prefs rename to guest_prefs3;
alter table dw1_identities rename to identities3;
alter table dw1_notifications rename to notifications3;
alter table dw1_page_paths rename to page_paths3;
alter table dw1_pages rename to pages3;
alter table dw1_posts_read_stats rename to post_read_stats3;
alter table dw1_role_page_settings rename to member_page_settings3;
alter table dw1_tenant_hosts rename to hosts3;
alter table dw1_tenants rename to sites3;
alter table dw1_users rename to users3;
alter table dw2_audit_log rename to audit_log3;
alter table dw2_blocks rename to blocks3;
alter table dw2_categories rename to categories3;
alter table dw2_invites rename to invites3;
alter table dw2_page_html rename to page_html3;
alter table dw2_post_actions rename to post_actions3;
alter table dw2_post_revisions rename to post_revisions3;
alter table dw2_posts rename to posts3;
alter table dw2_review_tasks rename to review_tasks3;
alter table dw2_upload_refs rename to upload_refs3;
alter table dw2_uploads rename to uploads3;
alter table message_members_3 rename to page_members3;
alter table settings_3 rename to settings3;


-- Oops. Delete duplicates:

delete from settings3
  where page_id is null and category_id is null and ctid not in (
    select max(s.ctid)
    from settings3 s where s.page_id is null and s.category_id is null
    group by s.site_id);

create unique index settings3_siteid__u on settings3 (site_id)
  where page_id is null and category_id is null;

