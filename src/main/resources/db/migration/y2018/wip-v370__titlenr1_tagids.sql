
alter table perms_on_pages3 alter constraint permsonpages_r_posts deferrable;

alter table post_read_stats3 drop constraint pstsrd_r_posts;  -- uses index dw2_posts_page_postnr__u
drop index dw2_posts_page_postnr__u;

drop index dw1_pstsrd_guest_ip__u;
drop index dw1_pstsrd_role__u;


-- Change title from nr 0 to nr 1, and page body from nr 1 to 2, and bump all other posts 1 step.

update posts3 set post_nr = post_nr + 1;
update posts3 set parent_nr = parent_nr + 1;
update posts3 set multireply = null where multireply is not null; -- not in use, just old test


update post_read_stats3 set post_nr = post_nr + 1;


create unique index posts_page_postnr_u on posts3 (site_id, page_id, post_nr);

-- New constraint.
alter table posts3 add constraint posts_c_postnr_ge_1 check (post_nr >= 1);
alter table posts3 add constraint posts_c_parent_not_title check (parent_nr <> 1);
alter table posts3 drop constraint dw2_posts_parent__c_not_title; -- old, cmps w 0

alter table post_read_stats3 add constraint pstsrd_r_posts
  foreign key (site_id, page_id, post_nr)
  references posts3(site_id, page_id, post_nr) deferrable;

create unique index pstsrd_guest_ip_u on post_read_stats3 (site_id, page_id, post_nr, ip)
  where user_id is null or user_id < 0;

create unique index pstsrd_user_u on post_read_stats3 (site_id, page_id, post_nr, user_id)
  where user_id is not null;

