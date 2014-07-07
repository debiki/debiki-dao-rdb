
create table DW1_POSTS_READ_STATS(
  SITE_ID varchar(32) not null,
  PAGE_ID varchar(32) not null,
  POST_ID int not null,
  USER_ID varchar(32) not null,
  -- The action that resulted in this post being considered read. No foreign key,
  -- because we might want to delete the action (but this post has still been read).
  READ_ACTION_ID int not null,
  READ_AT timestamp not null,
  constraint DW1_PSTSRD__P primary key (SITE_ID, PAGE_ID, POST_ID, USER_ID),
  -- Don't include the action id in the constraint because the action might be deleted.
  constraint DW1_PSTSRD_SITE_PAGE__R__PAGES foreign key (SITE_ID, PAGE_ID)
      references DW1_PAGES(TENANT, GUID)
);


-- Ignore duplicated inserts. If a post has been read, it has been read, doesn't matter
-- if the user reads it twice. (Trying to ignore unique key exceptions in the Scala
-- code results in the whole transaction failing.)
create or replace rule DW1_PSTSRD_IGNORE_DUPL_INS as
  on insert to DW1_POSTS_READ_STATS
  where exists (
      select 1
      from DW1_POSTS_READ_STATS
      where SITE_ID = new.SITE_ID
        and PAGE_ID = new.PAGE_ID
        and POST_ID = new.POST_ID
        and USER_ID = new.USER_ID)
   do instead nothing;


-- Only one vote per person.
create unique index DW1_PGAS_GUEST_VOTES__U
  on DW1_PAGE_ACTIONS(TENANT, PAGE_ID, POST_ID, GUEST_ID, TYPE)
  where TYPE like 'Vote%';

create unique index DW1_PGAS_ROLE_VOTES__U
  on DW1_PAGE_ACTIONS(TENANT, PAGE_ID, POST_ID, ROLE_ID, TYPE)
  where TYPE like 'Vote%';
