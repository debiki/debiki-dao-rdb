
alter table settings3 add column show_sub_communities boolean;
alter table settings3 add column language_code varchar;


-- There're both 2 and 3 letter language codes, and maybe would want to support
-- things like en_US too? So be a bit flexible with the length.
alter table settings3 add constraint settings3_c_langcode_len check (
  length(language_code) between 2 and 10);


alter table users3 add column deactivated_at timestamp;
alter table users3 add column deleted_at timestamp;
alter table users3 add column see_activity_min_trust_level int;


-- Deactivating an already deleted user = bug.
alter table users3 add constraint users_c_deact_bef_delete check (
  deactivated_at is null or deleted_at is null or deactivated_at <= deleted_at);

