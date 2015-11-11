
create or replace function is_valid_hash_path_suffix(text varchar) returns boolean as $$
    begin
        return text ~ '^[0-9a-z]/[0-9a-z]/[0-9a-z\.]+$';
    end;
$$ language plpgsql;

-- Remove some dupl code in old constraint checks:
alter table dw2_uploads drop constraint dw2_uploads_hashpathsuffix__c;
alter table dw2_uploads add constraint dw2_uploads_hashpathsuffix__c check(
    is_valid_hash_path_suffix(hash_path_suffix));

alter table dw2_upload_refs drop constraint dw2_uploadrefs_hashpathsuffix__c;
alter table dw2_upload_refs add constraint dw2_uploadrefs_hashpathsuffix__c check(
    is_valid_hash_path_suffix(hash_path_suffix));

-- Add forgotten check.
alter table dw2_uploads add constraint dw2_uploads_originalhashpathsuffix__c check(
    is_valid_hash_path_suffix(original_hash_path_suffix));


-- Add avatar image columns. One for tiny (25x25 like Discourse?), one for small (say 50x50),
-- and one for medium (say 400x400) images.
alter table dw1_users add column avatar_tiny_base_url varchar;
alter table dw1_users add column avatar_tiny_hash_path_suffix varchar;
alter table dw1_users add column avatar_small_base_url varchar;
alter table dw1_users add column avatar_small_hash_path_suffix varchar;
alter table dw1_users add column avatar_medium_base_url varchar;
alter table dw1_users add column avatar_medium_hash_path_suffix varchar;

-- Only authenticated users can have avatars.
alter table dw1_users add constraint dw1_users_avatars__c check(
    user_id > 0 or avatar_tiny_base_url is null);

-- Either all (tiny, small, medium) avatars images are specified, or none at all.
alter table dw1_users add constraint dw1_users_avatars_none_or_all__c check((
    avatar_tiny_base_url is null and avatar_tiny_hash_path_suffix is null and
    avatar_small_base_url is null and avatar_small_hash_path_suffix is null and
    avatar_medium_base_url is null and avatar_medium_hash_path_suffix is null)
    or (
    avatar_tiny_base_url is not null and avatar_tiny_hash_path_suffix is not null and
    avatar_small_base_url is not null and avatar_small_hash_path_suffix is not null and
    avatar_medium_base_url is not null and avatar_medium_hash_path_suffix is not null));

alter table dw1_users add constraint dw1_users_avatartinyhashpathsuffix__c check(
    is_valid_hash_path_suffix(avatar_tiny_hash_path_suffix));
alter table dw1_users add constraint dw1_users_avatarsmallhashpathsuffix__c check(
    is_valid_hash_path_suffix(avatar_small_hash_path_suffix));
alter table dw1_users add constraint dw1_users_avatarmediumhashpathsuffix__c check(
    is_valid_hash_path_suffix(avatar_medium_hash_path_suffix));

alter table dw1_users add constraint dw1_users_avatartinybaseurl__c_len check(
    length(avatar_tiny_base_url) between 1 and 100);
alter table dw1_users add constraint dw1_users_avatarsmallbaseurl__c_len check(
    length(avatar_small_base_url) between 1 and 100);
alter table dw1_users add constraint dw1_users_avatarmediumbaseurl__c_len check(
    length(avatar_medium_base_url) between 1 and 100);

create index dw1_users_avatartinybaseurl__i on dw1_users(avatar_tiny_base_url);
create index dw1_users_avatartinyhashpathsuffix__i on dw1_users(avatar_tiny_hash_path_suffix);
create index dw1_users_avatarsmallbaseurl__i on dw1_users(avatar_small_base_url);
create index dw1_users_avatarsmallhashpathsuffix__i on dw1_users(avatar_small_hash_path_suffix);
create index dw1_users_avatarmediumbaseurl__i on dw1_users(avatar_medium_base_url);
create index dw1_users_avatarmediumhashpathsuffix__i on dw1_users(avatar_medium_hash_path_suffix);


-- Fix bumped-at bugs: closed topics no longer bumped.
alter table dw1_pages drop constraint dw1_pages_replyat_bumpedat__c_le;

update dw1_pages set bumped_at = closed_at where bumped_at > closed_at;
alter table dw1_pages add constraint dw1_pages_bumpedat_le_closedat__c check (bumped_at <= closed_at);

