CREATE FUNCTION delete_page(the_site_id character varying, the_page_id character varying) RETURNS void
    LANGUAGE plpgsql
    AS $$
begin
delete from DW1_PAGE_ACTIONS where TENANT = the_site_id and PAGE_ID = the_page_id;
delete from DW1_PAGE_PATHS where TENANT = the_site_id and PAGE_ID = the_page_id;
delete from DW1_POSTS where SITE_ID = the_site_id and PAGE_ID = the_page_id;
delete from DW1_PAGES where TENANT = the_site_id and GUID = the_page_id;
end;
$$;


CREATE FUNCTION hex_to_int(hexval character varying) RETURNS integer
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
DECLARE
    result  int;
BEGIN
    EXECUTE 'SELECT x''' || hexval || '''::int' INTO result;
    RETURN result;
END;
$$;


CREATE FUNCTION inc_next_page_id(site_id character varying) RETURNS integer
    LANGUAGE plpgsql
    AS $$
declare
next_id int;
begin
update DW1_TENANTS
set NEXT_PAGE_ID = NEXT_PAGE_ID + 1
where ID = site_id
returning NEXT_PAGE_ID into next_id;
return next_id - 1;
end;
$$;


CREATE FUNCTION is_valid_css_class(text character varying) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
begin
    return text ~ '^[ a-zA-Z0-9_-]+$';
end;
$_$;


CREATE FUNCTION is_valid_hash_path(text character varying) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
begin
    return
    text ~ '^[0-9a-z]/[0-9a-z]/[0-9a-z\.]+$' or -- old, deprecated, remove later
    text ~ '^([a-z][a-z0-9]*/)?[0-9][0-9]?/[0-9a-z]/[0-9a-z]{2}/[0-9a-z\.]+$';
end;
$_$;


CREATE FUNCTION now_utc() RETURNS timestamp without time zone
    LANGUAGE plpgsql
    AS $$
begin
  return now() at time zone 'utc';
end;
$$;


CREATE FUNCTION string_id_to_int(string_id character varying) RETURNS character varying
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $_$
DECLARE
    result  int;
BEGIN
    SELECT 
      case
        when string_id ~ '^[0-9]+$' then string_id
        else case string_id
          when '0t' then '65501'
          when '0b' then '65502'
          when '0c' then '65503'
          -- I used `7` in test & dev though, but shorter ids = better, I hope 5 will do:
          else '' || substring('' || hex_to_int(substring(md5(string_id) from 1 for 7)) from 1 for 6)
        end
      end
      INTO result;
    RETURN result;
END;
$_$;


CREATE FUNCTION update_upload_ref_count(the_base_url character varying, the_hash_path character varying) RETURNS void
    LANGUAGE plpgsql
    AS $$
declare
    num_post_refs int;
    num_avatar_refs int;
    num_refs int;
begin
    -- (Don't use site_id here — dw2_uploads is for all sites)
    select count(*) into num_post_refs
        from dw2_upload_refs where base_url = the_base_url and hash_path = the_hash_path;
    select count(*) into num_avatar_refs
        from dw1_users
        where (avatar_tiny_base_url = the_base_url and avatar_tiny_hash_path = the_hash_path)
             or (avatar_small_base_url = the_base_url and avatar_small_hash_path = the_hash_path)
             or (avatar_medium_base_url = the_base_url and avatar_medium_hash_path = the_hash_path);
    num_refs = num_post_refs + num_avatar_refs;
    update dw2_uploads set
        updated_at = now_utc(),
        num_references = num_refs,
        unused_since =
            case when num_refs > 0 then null else
              case
                when unused_since is null then now_utc()
                else unused_since
              end
            end
        where base_url = the_base_url and hash_path = the_hash_path;
end $$;


