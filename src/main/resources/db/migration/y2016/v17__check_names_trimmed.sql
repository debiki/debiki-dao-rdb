
alter table users3 rename display_name to full_name;

update users3 set full_name = trim(full_name) where full_name <> trim(full_name);
alter table users3 add constraint users3_fullname_c_trim check (is_trimmed(full_name));
alter table users3 add constraint users3_username_c_blank check (not contains_blank(username));
alter table users3 add constraint users3_country_c_trim check (is_trimmed(country));

update users3 set website = null where contains_blank(website);
alter table users3 add constraint users3_website_c_trim check (not contains_blank(website));

