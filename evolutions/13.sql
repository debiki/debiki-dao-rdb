# This evolution adds num likes and num wrongs columns.


# --- !Ups


alter table DW1_PAGES add column CACHED_NUM_LIKES int not null default -1;
alter table DW1_PAGES add column CACHED_NUM_WRONGS int not null default -1;

-- There are actually some valid(?) empty titles, oddly enough.
alter table DW1_PAGES drop constraint DW1_PAGES_CACHEDTITLE__C_NE;


# --- !Downs


alter table DW1_PAGES drop column CACHED_NUM_LIKES;
alter table DW1_PAGES drop column CACHED_NUM_WRONGS;

alter table DW1_PAGES add constraint DW1_PAGES_CACHEDTITLE__C_NE check (
    btrim(cached_title::text) <> ''::text);

