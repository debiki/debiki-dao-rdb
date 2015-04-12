
-- FK from dw1_posts_read_stats to dw2_posts:
alter table dw1_posts_read_stats drop constraint dw1_pstsrd_site_page__r__pages;
alter table dw1_posts_read_stats add constraint dw1_pstsrd__r__posts
  foreign key (site_id, page_id, post_id) references dw2_posts(site_id, page_id, post_id);

