
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
