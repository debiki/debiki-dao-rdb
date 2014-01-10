-- This evolution is for embedded sites:
-- - Adds PAGE_URL column to DW1_PAGES, which can be used as;
--   1) The embedding page url, for embedded discussions
--   2) The canonical page url, for "normal" pages


# --- !Ups


alter table DW1_PAGES add column PAGE_URL varchar;

alter table DW1_PAGES add constraint DW1_PAGES_PAGEURL__C_LEN check (
    length(PAGE_URL) between 1 and 200);

alter table DW1_PAGES add constraint DW1_PAGES_PAGEURL__C_TRIM check (
    trim(PAGE_URL) = PAGE_URL);


# --- !Downs


alter table DW1_PAGES drop column PAGE_URL;


