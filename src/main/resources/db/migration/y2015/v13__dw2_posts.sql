
-- Create a System and an Unknown user, ids -1 and -3.
-- Reserving -2 for totally anonymous users.
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('2',  -1, 'System', 'T', 'system', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('3',  -1, 'System', 'T', 'system', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('18', -1, 'System', 'T', 'system', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('49', -1, 'System', 'T', 'system', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('35', -1, 'System', 'T', 'system', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('2',  -3, 'Unknown', null, 'unknown', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('3',  -3, 'Unknown', null, 'unknown', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('18', -3, 'Unknown', null, 'unknown', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('49', -3, 'Unknown', null, 'unknown', now());
insert into dw1_users(tenant, sno, display_name, superadmin, username, created_at) values ('35', -3, 'Unknown', null, 'unknown', now());

drop function inc_next_per_page_reply_id(
    site_id character varying, page_id character varying, step integer);

alter table dw1_pages drop column next_reply_id;
alter table dw1_pages add column num_replies_incl_deleted int not null default 0; -- TODO fill in, when migrating
alter table dw1_pages add column num_replies_excl_deleted int not null default 0; -- TODO fill in


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

  safe_version int,
  approved_source text,
  approved_html_sanitized text,
  approved_at timestamp,
  approved_by_id int,
  approved_version int,
  current_source_patch text,
  current_version int not null,

  collapsed_status varchar, -- 'R'ecursively / 'A'ncestor / 'O'nly this post
  collapsed_at timestamp,
  collapsed_by_id int,

  closed_status varchar, -- 'R'ecursively / 'A'ncestor
  closed_at timestamp,
  closed_by_id int,

  -- If a post has been flagged, it gets hidden. People can click to view it anyway, so that
  -- they can notify moderators if posts are being flagged and hidden inappropriately.
  hidden_at varchar,
  hidden_by_id int,
  hidden_reason varchar,

  deleted_status varchar, -- 'R'ecursively / 'A'ncestor / 'O'nly this post
  deleted_at timestamp,
  deleted_by_id int,

  pinned_position int,

  num_pending_flags int not null default 0,
  num_handled_flags int not null default 0,
  num_edit_suggestions int not null default 0,

  num_like_votes int not null default 0,
  num_wrong_votes int not null default 0,
  num_collapse_votes int not null default 0 ,
  num_times_read int not null default 0,

  constraint dw2_posts_site_page_post__p primary key (site_id, page_id, post_id),
  constraint dw2_posts__r__pages foreign key (site_id, page_id) references dw1_pages (tenant, guid),
  constraint dw2_posts__c_post_not_its_parent check (parent_post_id is null or post_id <> parent_post_id)

  -- safe_version is not null --> approved_version is not null
  -- prevent approved_version is null and current_source_patch is null
  -- prevent approved_version = ''
);


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
  constraint dw2_postacs__r__posts foreign key (site_id, page_id, post_id) references dw2_posts(site_id, page_id, post_id),
  constraint dw2_postacs__c_type_in check (type in (
    31, 32,          -- stars/bookmarks: yellow and blue
    41, 42, 43, 44,  -- votes: like, wrong, rude?, boring?, ?
    51, 52, 53))     -- flags: inappropriate, spam, off-topic
  -- del-at&by
  -- del & upd > cre
  -- upd >= del
);



/*
Indexes:
    "dw1_posts_pending_edit_suggs" btree (site_id, last_acted_upon_at) WHERE post_deleted_at IS NULL AND tree_deleted_at IS NULL AND num_pending_flags = 0 AND (last_approval_type::text = ANY (ARRAY['W'::character varying::text, 'A'::character varying::text, 'M'::character varying::text])) AND num_edits_to_review = 0 AND num_collapses_to_review = 0 AND num_uncollapses_to_review = 0 AND num_deletes_to_review = 0 AND num_undeletes_to_review = 0 AND (num_edit_suggestions > 0 OR num_collapse_post_votes_pro > 0 AND post_collapsed_at IS NULL OR num_uncollapse_post_votes_pro > 0 AND post_collapsed_at IS NOT NULL OR num_collapse_tree_votes_pro > 0 AND tree_collapsed_at IS NULL OR num_uncollapse_tree_votes_pro > 0 AND tree_collapsed_at IS NOT NULL OR num_delete_post_votes_pro > 0 AND post_deleted_at IS NULL OR num_undelete_post_votes_pro > 0 AND post_deleted_at IS NOT NULL OR num_delete_tree_votes_pro > 0 AND tree_deleted_at IS NULL OR num_undelete_tree_votes_pro > 0 AND tree_deleted_at IS NOT NULL)
    "dw1_posts_pending_flags" btree (site_id, num_pending_flags) WHERE post_deleted_at IS NULL AND tree_deleted_at IS NULL AND num_pending_flags > 0
    "dw1_posts_pending_nothing" btree (site_id, last_acted_upon_at) WHERE post_deleted_at IS NOT NULL OR tree_deleted_at IS NOT NULL OR num_pending_flags = 0 AND (last_approval_type::text = ANY (ARRAY['W'::character varying::text, 'A'::character varying::text, 'M'::character varying::text])) AND num_edits_to_review = 0 AND num_collapses_to_review = 0 AND num_uncollapses_to_review = 0 AND num_deletes_to_review = 0 AND num_undeletes_to_review = 0 AND num_edit_suggestions = 0 AND NOT (num_edit_suggestions > 0 OR num_collapse_post_votes_pro > 0 AND post_collapsed_at IS NULL OR num_uncollapse_post_votes_pro > 0 AND post_collapsed_at IS NOT NULL OR num_collapse_tree_votes_pro > 0 AND tree_collapsed_at IS NULL OR num_uncollapse_tree_votes_pro > 0 AND tree_collapsed_at IS NOT NULL OR num_delete_post_votes_pro > 0 AND post_deleted_at IS NULL OR num_undelete_post_votes_pro > 0 AND post_deleted_at IS NOT NULL OR num_delete_tree_votes_pro > 0 AND tree_deleted_at IS NULL OR num_undelete_tree_votes_pro > 0 AND tree_deleted_at IS NOT NULL)
    "dw1_posts_pending_sth" btree (site_id, last_acted_upon_at) WHERE post_deleted_at IS NULL AND tree_deleted_at IS NULL AND num_pending_flags = 0 AND (last_approval_type IS NULL OR last_approval_type::text = 'P'::text OR num_edits_to_review > 0 OR num_collapses_to_review > 0 OR num_uncollapses_to_review > 0 OR num_deletes_to_review > 0 OR num_undeletes_to_review > 0)
Check constraints:
    "dw1_posts_approval__c_in" CHECK (last_approval_type::text = ANY (ARRAY['P'::character varying::text, 'W'::character varying::text, 'A'::character varying::text]))
    "dw1_posts_multireply__c_num" CHECK (multireply::text ~ '[0-9,]'::text)
Referenced by:
    TABLE "dw1_notifications" CONSTRAINT "dw1_ntfs__r__posts" FOREIGN KEY (site_id, page_id, post_id) REFERENCES dw1_posts(site_id, page_id, post_id)
Triggers:
    dw1_posts_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_posts FOR EACH ROW EXECUTE PROCEDURE dw1_posts_summary()

*/


-- I'm removing the dw1_page_actions action id:
alter table dw1_posts_read_stats drop column read_action_id;

