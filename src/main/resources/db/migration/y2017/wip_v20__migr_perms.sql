-- Don't require email_for_every_new_post to be not-null for members.
alter table users3 drop constraint users_member__c_nn;
alter table users3 add constraint people_member_c_nn check (
  user_id < 0 or created_at is not null and username is not null);

-- Allow trust level 0 = strangers.
-- alter table users3 drop constraint users_lockedtrustlevel_c_betw;
-- alter table users3 drop constraint users_trustlevel_c_betw;
--
-- alter table users3 add constraint people_lockedtrustlevel_c_betw check (
--   locked_trust_level >= 0 and locked_trust_level <= 6);
-- alter table users3 add constraint people_trustlevel_c_betw check (
--   trust_level >= 0 and trust_level <= 6);

-- Groups don't have any auto-adjusting trust level.
alter table users3 alter trust_level drop default;
alter table users3 alter trust_level drop not null;
alter table users3 alter threat_level drop default;
alter table users3 alter threat_level drop not null;

-- Use the permission system instead.
alter table users3 drop column is_editor;

-- Convert users3 is_admin and is_owner 'T' to true:
-- First, drop 'T' constraints.
alter table users3 drop constraint dw1_users_isowner__c_b;
alter table users3 drop constraint dw1_users_superadm__c;
-- Then converst.
alter table users3 alter column is_admin type boolean using (
  case is_admin
    when 'T' then true
    else null
  end);
alter table users3 alter column is_owner type boolean using (
  case is_owner
    when 'T' then true
    else null
  end);

-- Cannot be both moderator and admin.
alter table users3 add constraint people_c_not_both_admin_mod check (
  not is_admin or not is_moderator);



-- TODO do this automatically for new sites:

-- Create groups for all trust levels, and staff, mods and admins.
insert into users3 (site_id, user_id, full_name, username, created_at)
  select
    s.id as site_id,
    10 as user_id,
    'Everyone' full_name,
    'everyone' as username,
    now_utc() as created_at
  from sites3 s;


insert into users3 (
  site_id, user_id, full_name, username, created_at, locked_trust_level, locked_threat_level)
  select
    s.id as site_id,
    11 as user_id,
    'New Members' full_name,
    'new_members' as username,
    now_utc() as created_at,
    1 as locked_trust_level,
    3 as locked_threat_level
  from sites3 s;


insert into users3 (
  site_id, user_id, full_name, username, created_at, locked_trust_level, locked_threat_level)
  select
    s.id as site_id,
    12 as user_id,
    'Basic Members' full_name,
    'basic_members' as username,
    now_utc() as created_at,
    2 as locked_trust_level,
    3 as locked_threat_level
  from sites3 s;


insert into users3 (
  site_id, user_id, full_name, username, created_at, locked_trust_level, locked_threat_level)
  select
    s.id as site_id,
    13 as user_id,
    'Full Members' full_name,
    'full_members' as username,
    now_utc() as created_at,
    3 as locked_trust_level,
    3 as locked_threat_level
  from sites3 s;


insert into users3 (
  site_id, user_id, full_name, username, created_at, locked_trust_level, locked_threat_level)
  select
    s.id as site_id,
    14 as user_id,
    'Trusted Members' full_name,
    'trusted_members' as username,
    now_utc() as created_at,
    4 as locked_trust_level,
    2 as locked_threat_level
  from sites3 s;


insert into users3 (
  site_id, user_id, full_name, username, created_at, locked_trust_level, locked_threat_level)
  select
    s.id as site_id,
    15 as user_id,
    'Regular Members' full_name,
    'regular_members' as username,
    now_utc() as created_at,
    5 as locked_trust_level,
    2 as locked_threat_level
  from sites3 s;


insert into users3 (
  site_id, user_id, full_name, username, created_at, locked_trust_level, locked_threat_level)
  select
    s.id as site_id,
    16 as user_id,
    'Core Members' full_name,
    'core_members' as username,
    now_utc() as created_at,
    6 as locked_trust_level,
    1 as locked_threat_level
  from sites3 s;


insert into users3 (site_id, user_id, full_name, username, created_at)
  select
    s.id as site_id,
    17 as user_id,
    'Staff' full_name,
    'staff' as username,
    now_utc() as created_at
  from sites3 s;


insert into users3 (site_id, user_id, full_name, username, created_at, is_moderator)
  select
    s.id as site_id,
    18 as user_id,
    'Moderators' full_name,
    'moderators' as username,
    now_utc() as created_at,
    true as is_moderator
  from sites3 s;


insert into users3 (site_id, user_id, full_name, username, created_at, is_admin)
  select
    s.id as site_id,
    19 as user_id,
    'Admins' full_name,
    'admins' as username,
    now_utc() as created_at,
    true as is_admin
  from sites3 s;




-- Insert default permissions for Everyone and Staff, on all categories.

-- TODO do this automatically when creating new cats !


-- Permissions for everyone to see, post topics and comments: (only on not-staff-only categories)
insert into perms_on_pages3(
  site_id,
  perm_id,
  for_people_id,
  on_category_id,
  may_create_page,
  may_post_comment,
  may_see)
  select
    cats.site_id,
    (10 * cats.id + 0) as perm_id,  -- 10 x + 0 = for everyone
    10 as for_people_id,  -- everyone
    cats.id as on_category_id,
    not cats.only_staff_may_create_topics and not cats.unlisted as may_create_page,
    true as may_post_comment,
    true as may_see
  from categories3 cats
  where cats.staff_only = false
    -- Exclude root categories.
    and  cats.parent_id is not null;


-- Permissions for staff to do everything:
insert into perms_on_pages3(
  site_id,
  perm_id,
  for_people_id,
  on_category_id,
  may_edit_page,
  may_edit_comment,
  may_edit_wiki,
  may_delete_page,
  may_delete_comment,
  may_create_page,
  may_post_comment,
  may_see)
  select
    cats.site_id,
    (10 * cats.id + 7) as perm_id,  -- 10 x + 7 = for staff
    17 as for_people_id,  -- staff
    cats.id as on_category_id,
    true as may_edit_page,
    true as may_edit_comment,
    true as may_edit_wiki,
    true as may_delete_page,
    true as may_delete_comment,
    true as may_create_page,
    true as may_post_comment,
    true as may_see
  from categories3 cats
  where
    -- Exclude root categories.
    cats.parent_id is not null;


-- Later:
-- alter table categories3 drop column staff_only;
-- alter table categories3 drop column only_staff_may_create_topics;

