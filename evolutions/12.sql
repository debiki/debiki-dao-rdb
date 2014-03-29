-- This evolution:
--  - Adds a Special Content ('SP') page type
--  - Adds a delete page function


# --- !Ups


-- Add 'SP'.
alter table DW1_PAGES drop constraint DW1_PAGES_PAGEROLE__C_IN;
alter table DW1_PAGES add constraint DW1_PAGES_PAGEROLE__C_IN
  check (PAGE_ROLE in ('G', 'EC', 'B', 'BP', 'FG', 'F', 'FT', 'W', 'WP', 'C', 'SP'));


-- Usage example:  select delete_page('tenant_id', 'page_id');
--
-- (Play framework thinks that each ';' ends an evolution statement, so in the
-- function body use ';;' which Play converts to a single ';'.)
--
create function delete_page(the_site_id varchar, the_page_id varchar) returns void as $$
begin
  delete from DW1_PAGE_ACTIONS where TENANT = the_site_id and PAGE_ID = the_page_id;;
  delete from DW1_PAGE_PATHS where TENANT = the_site_id and PAGE_ID = the_page_id;;
  delete from DW1_POSTS where SITE_ID = the_site_id and PAGE_ID = the_page_id;;
  delete from DW1_PAGES where TENANT = the_site_id and GUID = the_page_id;;
end;;
$$ language plpgsql;



# --- !Downs


-- Remove 'SP'.
alter table DW1_PAGES drop constraint DW1_PAGES_PAGEROLE__C_IN;
alter table DW1_PAGES add constraint DW1_PAGES_PAGEROLE__C_IN
  check (PAGE_ROLE in ('G', 'EC', 'B', 'BP', 'FG', 'F', 'FT', 'W', 'WP', 'C'));


drop function delete_page(the_site_id varchar, the_page_id varchar);

