
create function now_utc() returns timestamp as $$
begin
  return now() at time zone 'utc';
end;
$$ language plpgsql;

-- Create a System and an Unknown user, ids -1 and -3.
-- Reserving -2 for totally anonymous users.
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('2',  -1, 'System', 'T', 'system', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('3',  -1, 'System', 'T', 'system', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('18', -1, 'System', 'T', 'system', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('49', -1, 'System', 'T', 'system', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('35', -1, 'System', 'T', 'system', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('2',  -3, 'Unknown', null, 'unknown', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('3',  -3, 'Unknown', null, 'unknown', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('18', -3, 'Unknown', null, 'unknown', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('49', -3, 'Unknown', null, 'unknown', now_utc());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('35', -3, 'Unknown', null, 'unknown', now_utc());

drop function inc_next_per_page_reply_id(
    site_id character varying, page_id character varying, step integer);


-- Delete all old super complicated page config posts.
delete from dw1_posts_read_stats where post_id = 65503;
delete from dw1_page_actions where post_id = 65503;
delete from dw1_posts where post_id = 65503;


create table dw2_posts(
  site_id varchar not null,
  page_id varchar not null,
  post_id int not null,
  parent_post_id int,
  multireply varchar,

  created_at timestamp not null,
  created_by_id int not null,
  updated_at timestamp,

  last_edited_at timestamp,
  last_edited_by_id int,
  last_approved_edit_at timestamp,
  last_approved_edit_by_id int,
  num_distinct_editors int not null,
  num_edit_suggestions smallint not null default 0,
  last_edit_suggestion_at timestamp,

  safe_version int,
  approved_source text,
  approved_html_sanitized text,
  approved_at timestamp,
  approved_by_id int,
  approved_version int,
  current_source_patch text,
  current_version int not null,

  collapsed_status smallint not null,
  collapsed_at timestamp,
  collapsed_by_id int,

  closed_status smallint not null,
  closed_at timestamp,
  closed_by_id int,

  hidden_at timestamp,
  hidden_by_id int,
  hidden_reason varchar,

  deleted_status smallint not null,
  deleted_at timestamp,
  deleted_by_id int,

  pinned_position smallint,

  num_pending_flags smallint not null default 0,
  num_handled_flags smallint not null default 0,

  num_like_votes int not null default 0,
  num_wrong_votes int not null default 0,
  num_times_read int not null default 0,

  constraint dw2_posts_site_page_post__p primary key (site_id, page_id, post_id),
  constraint dw2_posts__r__pages foreign key (site_id, page_id) references dw1_pages(tenant, guid), -- ix: pk

  constraint dw2_posts__c_not_its_parent check (parent_post_id is null or post_id <> parent_post_id),
  constraint dw2_posts_multireply__c_num check (multireply ~ '[0-9,]'),

  constraint dw2_posts__c_last_edit check (
    (last_edited_at is null or last_edited_at >= created_at) and
    (last_edited_at is null = last_edited_by_id is null)),

  constraint dw2_posts__c_last_apr_edit_at check (
    (last_approved_edit_at is null or (
        last_approved_edit_at <= last_edited_at and last_edited_at is not null))),
  constraint dw2_posts__c_last_apr_edit_at_id check (
    (last_approved_edit_at is null = last_approved_edit_by_id is null)),

  constraint dw2_posts__c_last_edi_sug check (
    (num_edit_suggestions = 0 or last_edit_suggestion_at is not null) and
    (last_edit_suggestion_at is null or last_edit_suggestion_at >= created_at)),

  constraint dw2_posts__c_upd_at_ge_cre check (updated_at is null or updated_at >= created_at),
  constraint dw2_posts__c_apr_at_ge_cre check (approved_at is null or approved_at >= created_at),

  constraint dw2_posts__c_approved check (
    (approved_version is null = approved_at is null) and
    (approved_version is null = approved_by_id is null) and
    (approved_version is null = approved_source is null)),

  constraint dw2_posts__c_apr_html_src check (
    approved_html_sanitized is null or approved_source is not null),

  constraint dw2_posts__c_apr_src_ne check (
    approved_source is null or length(trim(approved_source)) >= 1),

  constraint dw2_posts__c_apr_html_ne check (
    approved_html_sanitized is null or length(trim(approved_html_sanitized)) >= 1),

  constraint dw2_posts__c_ne check (
    approved_source is not null or current_source_patch is not null),

  constraint dw2_posts__c_curpatch_ne check (
    current_source_patch is null or length(trim(current_source_patch)) >= 1),

  constraint dw2_posts__c_up_to_date_no_patch check (
    approved_version is null or (
        (current_version = approved_version) = (current_source_patch is null))),

  constraint dw2_posts__c_apr_ver_le_cur check (approved_version is null or approved_version <= current_version),
  constraint dw2_posts__c_saf_ver_le_apr check (
    (safe_version is null) or (safe_version <= approved_version and approved_version is not null)),

  constraint dw2_posts__c_collapsed check (
    (collapsed_at is null or collapsed_at >= created_at) and
    ((collapsed_status = 0) = collapsed_at is null) and
    ((collapsed_status = 0) = collapsed_by_id is null)),

  constraint dw2_posts__c_closed check (
    (closed_at is null or closed_at >= created_at) and
    ((closed_status = 0) = closed_at is null) and
    ((closed_status = 0) = closed_by_id is null)),

  constraint dw2_posts__c_deleted check (
    (deleted_at is null or deleted_at >= created_at) and
    ((deleted_status = 0) = deleted_at is null) and
    ((deleted_status = 0) = deleted_by_id is null)),

  constraint dw2_posts__c_hidden check (
    (hidden_at is null or hidden_at >= created_at) and
    (hidden_at is null = hidden_by_id is null) and
    (hidden_reason is null or hidden_at is not null)),

  constraint dw2_posts__c_counts_gez check (
    num_distinct_editors >= 0 and
    num_edit_suggestions >= 0 and
    num_pending_flags >= 0 and
    num_handled_flags >= 0 and
    num_like_votes >= 0 and
    num_wrong_votes >= 0 and
    num_times_read >= 0)
);


create index dw2_posts_numflags__i on dw2_posts(site_id, num_pending_flags) where
  deleted_status = 0 and
  num_pending_flags > 0;

create index dw2_posts_unapproved__i on dw2_posts(site_id, last_edited_at) where
  deleted_status = 0 and
  num_pending_flags = 0 and
  (approved_version is null or approved_version < current_version);

create index dw2_posts_pendingedits__i on dw2_posts(site_id, last_edit_suggestion_at) where
  deleted_status = 0 and
  num_pending_flags = 0 and
  approved_version = current_version and
  num_edit_suggestions > 0;



create table dw2_post_actions(
  site_id varchar not null,
  page_id varchar not null,
  post_id int not null,
  type smallint not null,
  sub_id smallint not null,
  created_by_id int not null,
  created_at timestamp not null,
  updated_at timestamp,
  deleted_at timestamp,
  deleted_by_id int,
  constraint dw2_postacs__p primary key (site_id, page_id, post_id, type, created_by_id, sub_id),
  constraint dw2_postacs__r__posts foreign key (site_id, page_id, post_id) references dw2_posts(site_id, page_id, post_id), -- ix: pk
  constraint dw2_postacs__c_type_in check (type in (
    31, 32,          -- stars/bookmarks: yellow and blue
    41, 42, 43, 44,  -- votes: like, wrong, rude?, boring?, ?
    51, 52, 53)),    -- flags: inappropriate, spam, off-topic
  constraint dw2_postacs__c_delat_by check (deleted_at is null = deleted_by_id is null),
  constraint dw2_postacs__c_updat_ge_delat check (updated_at >= deleted_at),
  constraint dw2_postacs__c_updat_ge_creat check (updated_at >= created_at),
  constraint dw2_postacs__c_delat_ge_creat check (deleted_at >= created_at)
);

create index dw2_postacs_page_byuser on dw2_post_actions(site_id, page_id, created_by_id);



-- I'm removing the dw1_page_actions action id:
alter table dw1_posts_read_stats drop column read_action_id;


-- The notifications table should reference the new tables instead of the old ones:

delete from dw1_notifications;
alter table dw1_notifications drop constraint dw1_ntfs__r__actions;
alter table dw1_notifications drop constraint dw1_ntfs__r__posts;

alter table dw1_notifications alter column by_user_id set not null;

alter table dw1_notifications drop column action_id;
alter table dw1_notifications add column action_type smallint;
alter table dw1_notifications add column action_sub_id smallint;

alter table dw1_notifications alter column by_user_id type int using (by_user_id::int);
alter table dw1_notifications alter column to_user_id type int using (to_user_id::int);

alter table dw1_notifications add constraint dw1_ntfs__r__posts
  foreign key (site_id, page_id, post_id) references dw2_posts(site_id, page_id, post_id);
  -- ix: dw1_ntfs_post__u

alter table dw1_notifications add constraint dw1_ntfs__r__postacs
  foreign key (site_id, page_id, post_id, action_type, by_user_id, action_sub_id) references
    dw2_post_actions(site_id, page_id, post_id, type, created_by_id, sub_id);
  -- ix: dw1_ntfs_post__u (covers most fields, not all)

alter table dw1_notifications add constraint dw1_ntfs__c_action check(
    action_type is not null = action_sub_id is not null);


-- Removes unneeded stuff from the pages table, renames columns, and adds num-replies columns.

alter table dw1_page_actions drop constraint dw1_pactions__r__pages; -- the 'sno' column

alter table dw1_pages drop column sno;
alter table dw1_pages rename column tenant to site_id;
alter table dw1_pages rename column guid to page_id;
alter table dw1_pages rename column cdati to created_at;
alter table dw1_pages rename column mdati to updated_at;
alter table dw1_pages rename column publ_dati to published_at;
alter table dw1_pages drop column cached_title;
alter table dw1_pages rename column sgfnt_mdati to bumped_at;

alter table dw1_pages drop column cached_author_display_name;
alter table dw1_pages rename column cached_author_user_id to author_id;
alter table dw1_pages alter column author_id set not null;
alter table dw1_pages alter column author_id type int using (author_id::int);

alter table dw1_pages drop column cached_num_posters;
alter table dw1_pages drop column cached_num_actions;
alter table dw1_pages drop column cached_num_posts_to_review;
alter table dw1_pages drop column cached_num_posts_deleted;
alter table dw1_pages drop column cached_num_replies_visible;
alter table dw1_pages drop column cached_last_visible_post_dati;

alter table dw1_pages rename column cached_num_child_pages to num_child_pages;
alter table dw1_pages rename column cached_num_likes to num_likes;
alter table dw1_pages rename column cached_num_wrongs to num_wrongs;

alter table dw1_pages add column num_replies_incl_deleted int not null default 0;
alter table dw1_pages add column num_replies_excl_deleted int not null default 0;

drop sequence dw1_pages_sno;


-- Rename 'tenant' columns to 'site_id'.