CLEAN_UP // remove guest_prefs3.version column, use the aduit_log3 table instead



alter table settings3 add column flag_fraction_to_close_topic real not null default 0.1;
alter table settings3 add column num_flags_to_close_topic int not null default 10;
alter table settings3 add column num_users_to_close_topic int not null default 3;



create table tags3(
  site_id
  tag_id
  tag_label
  num_tagged_pages
  num_tagged_total

create table post_tags3(
  site_id varchar not null,
  post_id int not null,
  tag_id varchar not null,  <--
  is_page bool not null  <--


user_tag_notfs
  site_id
  user_id
  tag_id
  notf_level

user_category_notfs
  site_id
  user_id
  tag_id
  notf_level



create table tag_labels3(
  site_id varchar,
  label_id int,
  label_text varchar not null,
  constraint taglbls_tagid__p primary key (site_id, label_id),
  constraint taglbls_labeltext__c_len check (length (label_text) between 1 and 200)
);

create table post_tags3(
  site_id varchar not null,
  post_id int,
  label_id int not null,
  is_page bool not null,
  constraint posttags_site_post__p primary key (site_id, post_id, label_id),
  constraint posttags__r__taglbls foreign key (site_id, label_id) references tag_labels3(site_id, label_id)
);
