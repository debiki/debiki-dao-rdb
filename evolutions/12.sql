-- This evolution adds a delete page function.


# --- !Ups


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


drop function delete_page(the_site_id varchar, the_page_id varchar);
