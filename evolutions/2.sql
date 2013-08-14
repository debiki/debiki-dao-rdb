# This evolution adds a full text search status column to DW1_PAGE_ACTIONS, for
# TYPE = 'Post' rows. It keeps track of into which ElasticSearch index each
# post has been indexed. And it's indexed itself, so the server can quickly
# find all unindexed posts.


# --- !Ups

alter table DW1_PAGE_ACTIONS add column INDEX_VERSION int;
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_INDEXVER_TYPE__C check (
  INDEX_VERSION is null or TYPE = 'Post');


-- Include site id so in the future it'll be possible to efficiently find
-- 100 posts from site A, then 100 from site B, then site C, etcetera,
-- so each site gets a fair time slot (avoid starvation).
create index DW1_PGAS_INDEXVERSION on DW1_PAGE_ACTIONS(INDEX_VERSION, TENANT)
  where TYPE = 'Post';

update DW1_PAGE_ACTIONS set INDEX_VERSION = 0 where type = 'Post';


# --- !Downs

drop index DW1_PGAS_INDEXVERSION;

alter table DW1_PAGE_ACTIONS drop constraint DW1_PGAS_INDEXVER_TYPE__C;

alter table DW1_PAGE_ACTIONS drop column INDEX_VERSION;
-- alter table DW1_PAGE_ACTIONS drop column VERSION;
