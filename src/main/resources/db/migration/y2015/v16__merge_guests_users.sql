
alter table dw1_ids_openid drop constraint dw1_idsoid_usr_tnt__r__users;
alter table dw1_emails_out drop constraint dw1_emlot__r__roles;
alter table dw1_emails_out drop constraint dw1_emlot__r__guests;
alter table dw1_role_page_settings drop constraint dw1_ropgst_site_role__r__roles;

alter table dw1_emails_out add column to_user_id int;
update dw1_emails_out set to_user_id = to_role_id::int;
update dw1_emails_out set to_user_id = -to_guest_id::int where to_user_id is null;
update dw1_emails_out set to_user_id = to_user_id + 90 where to_user_id >= 0;
alter table dw1_emails_out drop column to_guest_id;
alter table dw1_emails_out drop column to_role_id;

alter table dw1_guests alter column id type int using (-id::int);

alter table dw1_ids_openid rename column usr to user_id;
alter table dw1_ids_openid alter column user_id type int using (user_id::int);
update dw1_ids_openid set user_id = user_id + 90 where user_id >= 0;

alter table dw1_ids_openid rename column usr_orig to user_id_orig;
alter table dw1_ids_openid alter column user_id_orig type int using (user_id_orig::int);
update dw1_ids_openid set user_id_orig = user_id_orig + 90 where user_id_orig >= 0;

alter table dw1_notifications alter column by_user_id type int using (by_user_id::int);
update dw1_notifications set by_user_id = by_user_id + 90 where by_user_id >= 0;

alter table dw1_notifications alter column to_user_id type int using (to_user_id::int);
update dw1_notifications set to_user_id = to_user_id + 90 where to_user_id >= 0;

update dw1_pages set author_id = author_id + 90 where author_id >= 0;
alter table dw1_pages alter column deleted_by_id type int using (deleted_by_id::int);
update dw1_pages set deleted_by_id = deleted_by_id + 90 where deleted_by_id >= 0;

drop rule dw1_pstsrd_ignore_dupl_ins on dw1_posts_read_stats;
alter table dw1_posts_read_stats alter column user_id type int using (user_id::int);
update dw1_posts_read_stats set user_id = user_id + 90 where user_id >= 0;
create or replace rule dw1_pstsrd_ignore_dupl_ins as
  on insert to dw1_posts_read_stats
  where exists (
      select 1 from dw1_posts_read_stats
      where site_id = new.site_id
        and page_id = new.page_id
        and post_id = new.post_id
        and (user_id = new.user_id or ip = new.ip))
   do instead nothing;

alter table dw1_role_page_settings alter column role_id type int using (role_id::int);
update dw1_role_page_settings set role_id = role_id + 90 where role_id >= 0;

alter table dw1_users rename column sno to user_id;
alter table dw1_users alter column user_id type int using (user_id::int);
update dw1_users set user_id = user_id + 90 where user_id >= 0;

update dw2_post_actions set created_by_id = created_by_id + 90 where created_by_id >= 0;
update dw2_post_actions set deleted_by_id = deleted_by_id + 90 where deleted_by_id >= 0;

update dw2_posts set created_by_id = created_by_id + 90 where created_by_id >= 0;
update dw2_posts set last_edited_by_id = last_edited_by_id + 90 where last_edited_by_id >= 0;
update dw2_posts set last_approved_edit_by_id = last_approved_edit_by_id + 90 where last_approved_edit_by_id >= 0;
update dw2_posts set approved_by_id = approved_by_id + 90 where approved_by_id >= 0;
update dw2_posts set collapsed_by_id = collapsed_by_id + 90 where collapsed_by_id >= 0;
update dw2_posts set closed_by_id = closed_by_id + 90 where closed_by_id >= 0;
update dw2_posts set hidden_by_id = hidden_by_id + 90 where hidden_by_id >= 0;
update dw2_posts set deleted_by_id = deleted_by_id + 90 where deleted_by_id >= 0;
update dw2_posts set pinned_by_id = pinned_by_id + 90 where pinned_by_id >= 0;


-- Copy from dw1_guests to dw1_users:

-- user id >= 100 = authenticated user, -1 = system, -2 = totally anonymous, -3 = unknown,
-- <= -10 = a guest.

alter table dw1_users add column guest_location varchar;
alter table dw1_users add constraint dw1_users_gstloctn__c_len check (length(guest_location) < 100);

alter table dw1_users alter column created_at drop not null;

alter table dw1_users drop constraint dw1_users_email__c;
alter table dw1_users add constraint dw1_users_email__c check (email ~~ '%@%.%' or user_id < -1);

-- Forbid id 0. Reserve 1..99 for hard coded group ids, in case I include groups in this table
-- in the future.
alter table dw1_users add constraint dw1_users_id__c check (user_id < 0 or 100 <= user_id);

drop index dw1_users_site_email__u;
create unique index dw1_users_site_email__u on dw1_users(site_id, email) where user_id >= -1;

-- TODO move from v13 to here:
-- insert into dw1_users(site_id, user_id, display_name, guest_location, website)
--   select id,  -3, 'Unknown', '-', '-' from dw1_tenants;
update dw1_users set email = '-', guest_location = '-', website = '-'
    where user_id = -3;  -- for now instead


alter table dw1_users add constraint dw1_users_guest__c_nn check (
    user_id >= -1 or (
        display_name is not null and
        email is not null and
        guest_location is not null and
        website is not null));
create unique index dw1_user_guest__u on dw1_users(
    site_id, display_name, email, guest_location, website)
    where user_id < -1;

insert into dw1_users(site_id, user_id, display_name, email, guest_location, website)
    select site_id, id::int, name, email_addr, location, url from dw1_guests;


-- Add foreign keys.

alter table dw1_emails_out add constraint dw1_emlot__r__users foreign key (
    site_id, to_user_id) references dw1_users(site_id, user_id);

alter table dw1_ids_openid add constraint dw1_ids_userid__r__users foreign key (
    site_id, user_id) references dw1_users(site_id, user_id);
alter table dw1_ids_openid add constraint dw1_ids_useridorig__r__users foreign key (
    site_id, user_id_orig) references dw1_users(site_id, user_id);

alter table dw1_notifications add constraint dw1_ntfs_byuserid__r__users foreign key (
    site_id, by_user_id) references dw1_users(site_id, user_id);
alter table dw1_notifications add constraint dw1_ntfs_touserid__r__users foreign key (
    site_id, to_user_id) references dw1_users(site_id, user_id);

alter table dw1_pages add constraint dw1_pages_createdbyid__r__users foreign key (
    site_id, author_id) references dw1_users(site_id, user_id);
alter table dw1_pages add constraint dw1_pages_deletedbyid__r__users foreign key (
    site_id, deleted_by_id) references dw1_users(site_id, user_id);

alter table dw1_posts_read_stats add constraint dw1_pstsrd__r__users foreign key (
    site_id, user_id) references dw1_users(site_id, user_id);

alter table dw1_role_page_settings add constraint dw1_ropgst__r__users foreign key (
    site_id, role_id) references dw1_users(site_id, user_id);

alter table dw2_post_actions add constraint dw2_postacs_createdbyid__r__users foreign key (
    site_id, created_by_id) references dw1_users(site_id, user_id);
alter table dw2_post_actions add constraint dw2_postacs_deletedbyid__r__users foreign key (
    site_id, deleted_by_id) references dw1_users(site_id, user_id);

alter table dw2_posts add constraint dw2_posts_createdbyid__r__users foreign key (
    site_id, created_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_lasteditedbyid__r__users foreign key (
    site_id, last_edited_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_lastapprovededitbyid__r__users foreign key (
    site_id, last_approved_edit_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_approvedbyid__r__users foreign key (
    site_id, approved_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_collapsedbyid__r__users foreign key (
    site_id, collapsed_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_closedbyid__r__users foreign key (
    site_id, closed_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_hiddenbyid__r__users foreign key (
    site_id, hidden_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_deletedbyid__r__users foreign key (
    site_id, deleted_by_id) references dw1_users(site_id, user_id);
alter table dw2_posts add constraint dw2_posts_pinnedbyid__r__users foreign key (
    site_id, pinned_by_id) references dw1_users(site_id, user_id);


-- Change identity id from text to int.

alter table dw1_ids_openid rename sno to id;
alter table dw1_ids_openid alter id type int using (id::int);


-- Rename some stuff.

alter table dw1_ids_simple_email rename to dw1_guest_prefs;
alter table dw1_ids_openid rename to dw1_identities;


-- No longer needed.

drop sequence dw1_ids_sno;
drop sequence dw1_users_sno;
drop table dw1_guests;
drop table dw0_version;


-- TODO add indexes on user_id columns because now they're FKs.
-- user id < -1 --> not admin, not owner, ..
-- user id >= 100 -->
--   created_at


