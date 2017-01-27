
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

