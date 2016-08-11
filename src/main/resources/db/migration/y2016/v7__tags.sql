
-- Redefined in the repeatable migrations file, r__functions.sql.
create function is_valid_tag_label(text character varying) returns boolean language plpgsql as $_$
begin
    return false;
end;
$_$;


create table post_tags3(
  site_id varchar not null,
  post_id int not null,
  tag varchar not null,
  constraint posttags_site_post__p primary key (site_id, post_id, tag),
  constraint posttags__r__posts foreign key (site_id, post_id) references posts3 (site_id, unique_post_id),
  constraint posttags_tag__c_valid check (is_valid_tag_label(tag))
);

