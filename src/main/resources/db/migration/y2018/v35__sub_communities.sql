
alter table settings3 add column show_sub_communities boolean;
alter table settings3 add column language_code varchar;


-- There're both 2 and 3 letter language codes, and maybe would want to support
-- things like en_US too? So be a bit flexible with the length.
alter table settings3 add constraint settings3_c_langcode_len check (
  length(language_code) between 2 and 10);

