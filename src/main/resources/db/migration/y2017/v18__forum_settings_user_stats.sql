
-- like:  'abc' or 'abc|def|ghi' + accept chars in other languages / charsets like åäö éá etc, hmm.
-- '/' is allowed, so e.g. 'latest/questions' will work.
create or replace function is_menu_spec(text varchar) returns boolean
language plpgsql as $_$
begin
  return text  ~ '^\S+(\|\S+)?$' and text !~ '[!"\#\$\%\&''\(\)\*\+,\.:;\<=\>\?@\[\\\]\^`\{\}~]';
end;
$_$;

alter table settings3 add column forum_main_view varchar;
alter table settings3 add column forum_topics_sort_buttons varchar;
alter table settings3 add column forum_category_links varchar;
alter table settings3 add column forum_topics_layout int;
alter table settings3 add column forum_categories_layout int;

alter table settings3 add constraint settings_forummainview_c_in check (
  is_menu_spec(forum_main_view) and length(forum_main_view) between 1 and 100);

alter table settings3 add constraint settings_forumtopicssort_c_in check (
  is_menu_spec(forum_topics_sort_buttons) and length(forum_topics_sort_buttons) between 1 and 200);

alter table settings3 add constraint settings_forumcatlinks_c_in check (
  is_menu_spec(forum_category_links) and length(forum_category_links) between 1 and 300);

alter table settings3 add constraint settings_forumtopicslayout_c_in check (
  forum_topics_layout between 0 and 20);

alter table settings3 add constraint settings_forumcatslayout_c_in check (
  forum_categories_layout between 0 and 20);


-- Just type "unnamed oranization" instead, for now.
alter table settings3 drop constraint settings3_required_for_site__c;

-- Use forum_main_view instead.
alter table settings3 drop column show_forum_categories;


-- Will be updated frequently, so don't store in users3 (which contains large & fairly static rows).
create table user_stats3(
  site_id varchar not null,
  user_id int not null,
  last_seen_at timestamp not null,
  last_posted_at timestamp,
  last_emailed_at timestamp,
  last_emaill_link_clicked_at timestamp,
  last_emaill_failed_at timestamp,
  email_bounce_sum real not null default 0,
  first_seen_at timestamp not null,
  first_new_topic_at timestamp,
  first_discourse_reply_at timestamp,
  first_chat_message_at timestamp,
  topics_new_since timestamp not null,
  notfs_new_since_id int not null default 0,
  num_days_visited int not null default 0,
  num_minutes_reading int not null default 0,
  num_discourse_replies_read int not null default 0,
  num_discourse_replies_posted int not null default 0,
  num_discourse_topics_entered int not null default 0,
  num_discourse_topics_replied_in int not null default 0,
  num_discourse_topics_created int not null default 0,
  num_chat_messages_read int not null default 0,
  num_chat_messages_posted int not null default 0,
  num_chat_topics_entered int not null default 0,
  num_chat_topics_replied_in int not null default 0,
  num_chat_topics_created int not null default 0,
  num_likes_given int not null default 0,
  num_likes_received int not null default 0,
  num_solutions_provided int not null default 0,
  constraint memstats_p primary key (site_id, user_id),
  constraint memstats_r_people foreign key (site_id, user_id) references users3 (site_id, user_id),
  constraint memstats_c_lastseen_greatest check (
    (last_seen_at >= last_posted_at or last_posted_at is null) and
    (last_seen_at >= first_seen_at or first_seen_at is null) and
    (last_seen_at >= first_new_topic_at or first_new_topic_at is null) and
    (last_seen_at >= first_discourse_reply_at or first_discourse_reply_at is null) and
    (last_seen_at >= first_chat_message_at or first_chat_message_at is null) and
    (last_seen_at >= topics_new_since or topics_new_since is null)),
  constraint memstats_c_firstseen_smallest check (
    (first_seen_at <= last_posted_at or last_posted_at is null) and
    (first_seen_at <= first_new_topic_at or first_new_topic_at is null) and
    (first_seen_at <= first_discourse_reply_at or first_discourse_reply_at is null) and
    (first_seen_at <= first_chat_message_at or first_chat_message_at is null)),
  constraint memstats_c_gez check (
    email_bounce_sum >= 0 and
    notfs_new_since_id >= 0 and
    num_days_visited >= 0 and
    num_minutes_reading >= 0 and
    num_discourse_replies_read >= 0 and
    num_discourse_replies_posted >= 0 and
    num_discourse_topics_entered >= 0 and
    num_discourse_topics_replied_in >= 0 and
    num_discourse_topics_created >= 0 and
    num_chat_messages_read >= 0 and
    num_chat_messages_posted >= 0 and
    num_chat_topics_entered >= 0 and
    num_chat_topics_replied_in >= 0 and
    num_chat_topics_created >= 0 and
    num_likes_given >= 0 and
    num_likes_received >= 0 and
    num_solutions_provided >= 0)
);

-- Hmm, this seems like interesting?
create index userstats_lastseen_i on user_stats3 (site_id, last_seen_at desc);

-- No one should be without statistics.
insert into user_stats3 (site_id, user_id, last_seen_at, first_seen_at, topics_new_since)
  select site_id, user_id, created_at, created_at, created_at from users3;

create table user_visit_stats3(
  site_id varchar not null,
  user_id int not null,
  visit_date date not null,
  num_minutes_reading int not null default 0,
  num_discourse_replies_read int not null default 0,
  num_discourse_topics_entered int not null default 0,
  num_chat_messages_read int not null default 0,
  num_chat_topics_entered int not null default 0,
  constraint uservisitstats_p primary key (site_id, user_id, visit_date),
  constraint uservisitstats_r_people foreign key (site_id, user_id) references users3 (site_id, user_id),
  constraint uservisitstats_c_gez check (
    num_minutes_reading >= 0
    and num_discourse_replies_read >= 0
    and num_discourse_topics_entered >= 0
    and num_chat_messages_read >= 0
    and num_chat_topics_entered >= 0)
);


