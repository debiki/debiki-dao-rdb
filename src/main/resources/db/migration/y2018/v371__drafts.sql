-- alter table posts3 rename column unique_post_id to post_id;

create table drafts3 (
  site_id int not null,
  by_user_id int not null,
  draft_nr int not null,
  created_at timestamp not null,
  last_edited_at timestamp,
  auto_post_at timestamp,
  auto_post_publ_status smallint,
  deleted_at timestamp,
  new_topic_category_id int,
  new_topic_type int,
  message_to_user_id int,
  edit_post_id int,
  reply_to_page_id varchar,
  reply_to_post_nr int,
  reply_type smallint,
  reply_whisper_to_user_id int,
  title varchar not null,
  text varchar not null,

  constraint drafts_nr_p primary key (site_id, by_user_id, draft_nr),

	-- ix: pk
  constraint drafts_author_r_users foreign key (
      site_id, by_user_id) references users3 (site_id, user_id) deferrable,
	-- ix: drafts_category_i
  constraint drafts_newtopiccat_r_cats foreign key (
      site_id, new_topic_category_id) references categories3 (site_id, id) deferrable,
  -- ix: drafts_replytopostnr_user_u
  constraint drafts_posttoreplyto_r_posts foreign key (
      site_id, reply_to_page_id, reply_to_post_nr) references posts3 (site_id, page_id, post_nr) deferrable,
	-- ix: drafts_editpostid_user_u
  constraint drafts_posttoedit_r_posts foreign key (
      site_id, edit_post_id) references posts3 (site_id, unique_post_id) deferrable,
	-- ix: drafts_messagetouser_u
  constraint drafts_usertomessage_r_users foreign key (
      site_id, message_to_user_id) references users3 (site_id, user_id) deferrable,
	-- ix: drafts_whispertouser_i
	-- and will be unique per post, right, because: drafts_replytopostnr_user_u
  constraint drafts_usertowhisperto_r_users foreign key (
      site_id, reply_whisper_to_user_id) references users3 (site_id, user_id) deferrable,

  constraint drafts_c_nr_gte_1 check (draft_nr >= 1),
  constraint drafts_c_createdat_lte_lasteditedat check (created_at <= last_edited_at),
  constraint drafts_c_createdat_lte_autopublat check (created_at <= auto_post_at),
  constraint drafts_c_createdat_lte_deletedat check (created_at <= deleted_at),
  constraint drafts_c_lasteditedat_lte_deletedat check (last_edited_at <= deleted_at),
  constraint drafts_c_autopostat_lte_deletedat check (auto_post_at <= deleted_at),
  constraint drafts_c_page_post_null_eq check ((reply_to_page_id is null) = (reply_to_post_nr is null)),

	-- Should be a draft for ... something.
  constraint drafts_c_for_something check (
    new_topic_category_id is not null or
    message_to_user_id is not null or
    edit_post_id is not null or
    reply_to_page_id is not null),

	-- Cannot be a draft for many things at the same time.
	-- Either for a new topic,
  constraint drafts_c_is_new_topic_only check (
    new_topic_category_id is null or (
        message_to_user_id is null and
        edit_post_id is null and
        reply_to_page_id is null)),
	-- or a direct message,
  constraint drafts_c_is_dir_msg_only check (
    message_to_user_id is null or (
        new_topic_category_id is null and
        edit_post_id is null and
        reply_to_page_id is null)),
	-- or an edit,
  constraint drafts_c_is_edit_only check (
    edit_post_id is null or (
        new_topic_category_id is null and
        message_to_user_id is null and
        reply_to_page_id is null)),
	-- or a reply.
  constraint drafts_c_is_reply_only check (
    reply_to_page_id is null or (
        new_topic_category_id is null and
        edit_post_id is null and
        message_to_user_id is null))
);


create index drafts_byuser_editedat_i on drafts3 (
  site_id, by_user_id, coalesce(last_edited_at, created_at)) where deleted_at is null;

create index drafts_byuser_deldat_i on drafts3 (
  site_id, by_user_id, deleted_at desc) where deleted_at is not null;


create index drafts_category_i on drafts3 (
    site_id, new_topic_category_id);
create unique index drafts_messagetouser_u on drafts3 (
    site_id, message_to_user_id, by_user_id);
create index drafts_whispertouser_i on drafts3 (
    site_id, reply_whisper_to_user_id);
create unique index drafts_replytopostnr_user_u on drafts3 (
    site_id, reply_to_page_id, reply_to_post_nr, by_user_id);
create unique index drafts_editpostid_user_u on drafts3 (
    site_id, edit_post_id, by_user_id);

