
alter table settings3 add column num_flags_to_hide_post int;
alter table settings3 add column cooldown_minutes_after_flagged_hidden int;

alter table settings3 add column num_flags_to_block_new_user int;
alter table settings3 add column num_flaggers_to_block_new_user int;
alter table settings3 add column notify_mods_if_user_blocked boolean;

alter table settings3 add column regular_member_flag_weight real;
alter table settings3 add column core_member_flag_weight real;


alter table settings3 drop constraint settings3_only_for_site__c;

alter table settings3 add constraint settings3_only_for_site__c check (
  category_id is null and
  page_id is null
  or
  user_must_be_auth is null and
  user_must_be_approved is null and
  allow_guest_login is null and
  num_first_posts_to_review is null and
  num_first_posts_to_approve is null and
  num_first_posts_to_allow is null and
  org_domain is null and
  org_full_name is null and
  org_short_name is null and
  contrib_agreement is null and
  content_license is null and
  google_analytics_id is null and
  experimental is null and
  many_sections is null and
  num_flags_to_hide_post is null and
  cooldown_minutes_after_flagged_hidden is null and
  num_flags_to_block_new_user is null and
  num_flaggers_to_block_new_user is null and
  notify_mods_if_user_blocked is null and
  regular_member_flag_weight is null and
  core_member_flag_weight is null);

alter table settings3 add constraint settings3_flags__c_gez check (
  num_flags_to_hide_post >= 0 and
  cooldown_minutes_after_flagged_hidden >= 0 and
  num_flags_to_block_new_user >= 0 and
  num_flaggers_to_block_new_user >= 0);

alter table settings3 add constraint settings3_flag_weight__c_ge check (
  regular_member_flag_weight >= 1.0 and
  core_member_flag_weight >= regular_member_flag_weight);

