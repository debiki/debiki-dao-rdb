
-- Redefined in the repeatable migrations file, r__functions.sql.
-------------

create function is_valid_tag_label(text character varying) returns boolean language plpgsql as $_$
begin
    return false;
end;
$_$;

create function is_valid_notf_level(notf_level int) returns boolean language plpgsql as $_$
begin
    return false;
end;
$_$;


-- Tag tables
-------------

create table post_tags3(
  site_id varchar not null,
  post_id int not null,
  tag varchar not null,
  is_page bool not null,
  constraint posttags_site_post__p primary key (site_id, post_id, tag),
  constraint posttags__r__posts foreign key (site_id, post_id) references posts3 (site_id, unique_post_id),
  constraint posttags_tag__c_valid check (is_valid_tag_label(tag))
);


create table tag_notf_levels3(
  site_id varchar not null,
  user_id int not null,
  tag varchar not null,
  notf_level int not null,
  constraint tagnotflvl_site_user_tag__p primary key (site_id, user_id, tag),
  constraint tagnotflvl__r__users foreign key (site_id, user_id) references users3 (site_id, user_id),
  constraint tagnotflvl_notf_lvl check (is_valid_notf_level(notf_level))
);


create table category_notf_levels3(
  site_id varchar not null,
  user_id int not null,
  category_id int not null,
  notf_level int not null,
  constraint catnotflvl_site_user_cat__p primary key (site_id, user_id, category_id),
  constraint catnotflvl__r__users foreign key (site_id, user_id) references users3 (site_id, user_id),
  constraint catnotflvl_notf_lvl check (is_valid_notf_level(notf_level))
);
