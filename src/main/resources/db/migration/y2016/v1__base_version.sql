--
-- PostgreSQL database dump
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET client_min_messages = warning;

SET SESSION AUTHORIZATION DEFAULT;

--
-- Name: plpgsql; Type: EXTENSION; Schema: -; Owner: 
--

CREATE EXTENSION IF NOT EXISTS plpgsql WITH SCHEMA pg_catalog;


--
-- Name: EXTENSION plpgsql; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION plpgsql IS 'PL/pgSQL procedural language';


SET SESSION AUTHORIZATION 'debiki_dev';

SET search_path = public, pg_catalog;

--
-- Name: delete_page(character varying, character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

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


--
-- Name: dw1_emails_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_emails_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    begin
        -- Sent emails cannot be made unset, so ignore deletes.
        if (tg_op = 'UPDATE') then
            if (old.sent_on is null and new.sent_on is not null) then
                update dw1_tenants
                    set num_emails_sent = num_emails_sent + 1
                    where id = new.site_id;
            end if;
        elsif (tg_op = 'INSERT') then
            if (new.sent_on is not null) then
                update dw1_tenants
                    set num_emails_sent = num_emails_sent + 1
                    where id = new.site_id;
            end if;
        end if;
        return null;
    end;
$$;


--
-- Name: dw1_guests_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_guests_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_guests = num_guests + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw1_identities_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_identities_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_identities = num_identities + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw1_notfs_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_notfs_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_notfs = num_notfs + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw1_pages_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_pages_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_pages = num_pages + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw1_posts_read_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_posts_read_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_posts_read = num_posts_read + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw1_role_page_settings_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_role_page_settings_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_role_settings = num_role_settings + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw1_roles_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw1_roles_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_roles = num_roles + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw2_post_actions_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw2_post_actions_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            return null;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_actions = num_actions + delta_rows
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: dw2_posts_summary(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION dw2_posts_summary() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        delta_text_bytes integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            delta_text_bytes =
                - coalesce(length(old.approved_source), 0)
                - coalesce(length(old.curr_rev_source_patch), 0);
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            delta_text_bytes =
                + coalesce(length(new.approved_source), 0)
                + coalesce(length(new.curr_rev_source_patch), 0)
                - coalesce(length(old.approved_source), 0)
                - coalesce(length(old.curr_rev_source_patch), 0);
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            delta_text_bytes =
                + coalesce(length(new.approved_source), 0)
                + coalesce(length(new.curr_rev_source_patch), 0);
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_posts = num_posts + delta_rows,
                num_post_text_bytes = num_post_text_bytes + delta_text_bytes
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: hex_to_int(character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

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


--
-- Name: inc_next_page_id(character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

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


--
-- Name: is_valid_css_class(character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION is_valid_css_class(text character varying) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
begin
    return text ~ '^[ a-zA-Z0-9_-]+$';
end;
$_$;


--
-- Name: is_valid_hash_path(character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION is_valid_hash_path(text character varying) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
begin
    return
    text ~ '^[0-9a-z]/[0-9a-z]/[0-9a-z\.]+$' or -- old, deprecated, remove later
    text ~ '^([a-z][a-z0-9]*/)?[0-9][0-9]?/[0-9a-z]/[0-9a-z]{2}/[0-9a-z\.]+$';
end;
$_$;


--
-- Name: now_utc(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION now_utc() RETURNS timestamp without time zone
    LANGUAGE plpgsql
    AS $$
begin
  return now() at time zone 'utc';
end;
$$;


--
-- Name: string_id_to_int(character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

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


--
-- Name: sum_post_revs_quota_3(); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

CREATE FUNCTION sum_post_revs_quota_3() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    declare
        delta_rows integer;
        delta_bytes integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            delta_bytes =
                - coalesce(length(old.source_patch), 0)
                - coalesce(length(old.full_source), 0)
                - coalesce(length(old.title), 0);
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            delta_bytes =
                + coalesce(length(new.source_patch), 0)
                + coalesce(length(new.full_source), 0)
                + coalesce(length(new.title), 0),
                - coalesce(length(old.source_patch), 0)
                - coalesce(length(old.full_source), 0)
                - coalesce(length(old.title), 0);
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            delta_bytes =
                + coalesce(length(new.source_patch), 0)
                + coalesce(length(new.full_source), 0)
                + coalesce(length(new.title), 0);
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_post_revisions = num_post_revisions + delta_rows,
                num_post_rev_bytes = num_post_rev_bytes + delta_bytes
            where id = site_id;
        return null;
    end;
$$;


--
-- Name: update_upload_ref_count(character varying, character varying); Type: FUNCTION; Schema: public; Owner: debiki_dev
--

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


SET default_with_oids = false;

--
-- Name: dw1_emails_out; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_emails_out (
    site_id character varying(32) NOT NULL,
    id character varying(32) NOT NULL,
    sent_to character varying NOT NULL,
    sent_on timestamp without time zone,
    subject character varying NOT NULL,
    body_html character varying NOT NULL,
    provider_email_id character varying,
    failure_type character varying(1) DEFAULT NULL::character varying,
    failure_text character varying,
    failure_time timestamp without time zone,
    type character varying NOT NULL,
    created_at timestamp without time zone NOT NULL,
    to_user_id integer,
    CONSTRAINT dw1_emlot_bodyhtml__c_len CHECK (((length((body_html)::text) >= 1) AND (length((body_html)::text) <= 5000))),
    CONSTRAINT dw1_emlot_created_sent__c_le CHECK ((created_at <= sent_on)),
    CONSTRAINT dw1_emlot_failtext_type__c CHECK (((failure_text IS NULL) = (failure_type IS NULL))),
    CONSTRAINT dw1_emlot_failtime_type__c CHECK (((failure_time IS NULL) = (failure_type IS NULL))),
    CONSTRAINT dw1_emlot_failtype__c CHECK (((failure_type)::text = ANY (ARRAY[('B'::character varying)::text, ('R'::character varying)::text, ('C'::character varying)::text, ('O'::character varying)::text]))),
    CONSTRAINT dw1_emlot_failuretext__c_len CHECK ((length((failure_text)::text) <= 10000)),
    CONSTRAINT dw1_emlot_provideremailid__c_len CHECK ((length((provider_email_id)::text) <= 200)),
    CONSTRAINT dw1_emlot_sentto__c_len CHECK ((length((sent_to)::text) <= 200)),
    CONSTRAINT dw1_emlot_subject__c_len CHECK (((length((subject)::text) >= 1) AND (length((subject)::text) <= 200))),
    CONSTRAINT dw1_emlot_type__c_in CHECK (((type)::text = ANY (ARRAY[('Notf'::character varying)::text, ('CrAc'::character varying)::text, ('RsPw'::character varying)::text, ('Invt'::character varying)::text, ('InAc'::character varying)::text, ('InPw'::character varying)::text])))
);


--
-- Name: dw1_guest_prefs; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_guest_prefs (
    site_id character varying(32) NOT NULL,
    ctime timestamp without time zone NOT NULL,
    version character(1) NOT NULL,
    email character varying(100) NOT NULL,
    email_notfs character varying(1) NOT NULL,
    CONSTRAINT dw1_idsmpleml_email__c CHECK (((email)::text ~~ '%@%.%'::text)),
    CONSTRAINT dw1_idsmpleml_notfs__c CHECK (((email_notfs)::text = ANY (ARRAY[('R'::character varying)::text, ('N'::character varying)::text, ('F'::character varying)::text]))),
    CONSTRAINT dw1_idsmpleml_version__c CHECK ((version = ANY (ARRAY['C'::bpchar, 'O'::bpchar])))
);


--
-- Name: dw1_identities; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_identities (
    id integer NOT NULL,
    site_id character varying(32) NOT NULL,
    user_id integer NOT NULL,
    user_id_orig integer NOT NULL,
    oid_claimed_id character varying(500),
    oid_op_local_id character varying(500),
    oid_realm character varying(100),
    oid_endpoint character varying(100),
    oid_version character varying(100),
    first_name character varying(100),
    email character varying(100),
    country character varying(100),
    cdati timestamp without time zone DEFAULT now() NOT NULL,
    last_name character varying,
    full_name character varying,
    avatar_url character varying,
    securesocial_provider_id character varying,
    securesocial_user_id character varying,
    auth_method character varying,
    CONSTRAINT dw1_ids_authmethod__c_len CHECK ((length((auth_method)::text) < 50)),
    CONSTRAINT dw1_ids_avatarurl__c_len CHECK ((length((avatar_url)::text) < 250)),
    CONSTRAINT dw1_ids_email__c_len CHECK (((length((email)::text) >= 1) AND (length((email)::text) <= 100))),
    CONSTRAINT dw1_ids_fullname__c_len CHECK ((length((full_name)::text) < 100)),
    CONSTRAINT dw1_ids_lastname__c_len CHECK ((length((last_name)::text) < 100)),
    CONSTRAINT dw1_ids_ssproviderid__c_len CHECK ((length((securesocial_provider_id)::text) < 500)),
    CONSTRAINT dw1_ids_ssuserid__c_len CHECK ((length((securesocial_user_id)::text) < 500)),
    CONSTRAINT dw1_idsoid_sno_not_0__c CHECK (((id)::text <> '0'::text))
);


--
-- Name: dw1_notifications; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_notifications (
    site_id character varying NOT NULL,
    notf_type smallint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    page_id character varying,
    by_user_id integer NOT NULL,
    to_user_id integer NOT NULL,
    email_id character varying,
    email_status smallint DEFAULT 1 NOT NULL,
    seen_at timestamp without time zone,
    unique_post_id integer,
    action_type smallint,
    action_sub_id smallint,
    notf_id integer NOT NULL,
    CONSTRAINT dw1_notfs_emailstatus__c_in CHECK (((email_status >= 1) AND (email_status <= 20))),
    CONSTRAINT dw1_notfs_seenat_ge_createdat__c CHECK ((seen_at > created_at)),
    CONSTRAINT dw1_notfs_type__c_in CHECK (((notf_type >= 1) AND (notf_type <= 100))),
    CONSTRAINT dw1_ntfs__c_action CHECK (((action_type IS NOT NULL) = (action_sub_id IS NOT NULL))),
    CONSTRAINT dw1_ntfs_by_to__c_ne CHECK (((by_user_id)::text <> (to_user_id)::text))
);


--
-- Name: dw1_page_paths; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_page_paths (
    site_id character varying(32) NOT NULL,
    parent_folder character varying(100) NOT NULL,
    page_id character varying(32) NOT NULL,
    show_id character varying(1) NOT NULL,
    page_slug character varying(100) NOT NULL,
    cdati timestamp without time zone DEFAULT now() NOT NULL,
    canonical_dati timestamp without time zone DEFAULT now() NOT NULL,
    canonical character varying(1) NOT NULL,
    CONSTRAINT dw1_pgpths_cdati_mdati__c_le CHECK ((cdati <= canonical_dati)),
    CONSTRAINT dw1_pgpths_cncl__c CHECK (((canonical)::text = ANY (ARRAY[('C'::character varying)::text, ('R'::character varying)::text]))),
    CONSTRAINT dw1_pgpths_folder__c_dash CHECK (((parent_folder)::text !~~ '%/-%'::text)),
    CONSTRAINT dw1_pgpths_folder__c_start CHECK (((parent_folder)::text ~~ '/%'::text)),
    CONSTRAINT dw1_pgpths_showid__c_in CHECK (((show_id)::text = ANY (ARRAY[('T'::character varying)::text, ('F'::character varying)::text]))),
    CONSTRAINT dw1_pgpths_slug__c_ne CHECK ((btrim((page_slug)::text) <> ''::text))
);


--
-- Name: dw1_pages; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_pages (
    site_id character varying(32) NOT NULL,
    page_id character varying(32) NOT NULL,
    page_role smallint NOT NULL,
    category_id integer,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    published_at timestamp without time zone DEFAULT now(),
    bumped_at timestamp without time zone NOT NULL,
    author_id integer NOT NULL,
    num_child_pages integer DEFAULT 0 NOT NULL,
    embedding_page_url character varying,
    num_likes integer DEFAULT 0 NOT NULL,
    num_wrongs integer DEFAULT 0 NOT NULL,
    deleted_at timestamp without time zone,
    num_replies_visible integer DEFAULT 0 NOT NULL,
    num_replies_to_review integer DEFAULT 0 NOT NULL,
    num_replies_total integer DEFAULT 0 NOT NULL,
    num_bury_votes integer DEFAULT 0 NOT NULL,
    num_unwanted_votes integer DEFAULT 0 NOT NULL,
    last_reply_at timestamp without time zone,
    pin_order smallint,
    pin_where smallint,
    num_op_like_votes integer DEFAULT 0 NOT NULL,
    num_op_wrong_votes integer DEFAULT 0 NOT NULL,
    num_op_bury_votes integer DEFAULT 0 NOT NULL,
    num_op_unwanted_votes integer DEFAULT 0 NOT NULL,
    num_op_replies_visible integer DEFAULT 0 NOT NULL,
    answered_at timestamp without time zone,
    answer_post_id integer,
    done_at timestamp without time zone,
    closed_at timestamp without time zone,
    locked_at timestamp without time zone,
    frozen_at timestamp without time zone,
    unwanted_at timestamp without time zone,
    planned_at timestamp without time zone,
    version integer DEFAULT 1 NOT NULL,
    last_reply_by_id integer,
    frequent_poster_1_id integer,
    frequent_poster_2_id integer,
    frequent_poster_3_id integer,
    frequent_poster_4_id integer,
    html_tag_css_classes character varying,
    html_head_title character varying,
    html_head_description character varying,
    CONSTRAINT dw1_pages__c_closed_if_done_answered CHECK ((((done_at IS NULL) AND (answered_at IS NULL)) OR (closed_at IS NOT NULL))),
    CONSTRAINT dw1_pages__c_has_category CHECK (((page_role <> ALL (ARRAY[6, 7, 9])) OR (category_id IS NOT NULL))),
    CONSTRAINT dw1_pages__c_votes_gez CHECK (((((num_likes >= 0) AND (num_wrongs >= 0)) AND (num_bury_votes >= 0)) AND (num_unwanted_votes >= 0))),
    CONSTRAINT dw1_pages_answerat_answerpostid__c CHECK (((answered_at IS NULL) = (answer_post_id IS NULL))),
    CONSTRAINT dw1_pages_bumpedat_le_closedat__c CHECK ((bumped_at <= closed_at)),
    CONSTRAINT dw1_pages_cdati_mdati__c_le CHECK ((created_at <= updated_at)),
    CONSTRAINT dw1_pages_cdati_publdati__c_le CHECK ((created_at <= published_at)),
    CONSTRAINT dw1_pages_cdati_smdati__c_le CHECK ((created_at <= bumped_at)),
    CONSTRAINT dw1_pages_createdat_closedat__c_lt CHECK ((created_at <= closed_at)),
    CONSTRAINT dw1_pages_createdat_deletedat__c_lt CHECK ((created_at <= deleted_at)),
    CONSTRAINT dw1_pages_createdat_doneat__c_lt CHECK ((created_at <= done_at)),
    CONSTRAINT dw1_pages_createdat_frozenat__c_lt CHECK ((created_at <= frozen_at)),
    CONSTRAINT dw1_pages_createdat_lockedat_at CHECK ((created_at <= locked_at)),
    CONSTRAINT dw1_pages_createdat_plannedat__c_le CHECK ((created_at <= planned_at)),
    CONSTRAINT dw1_pages_createdat_replyat__c_le CHECK ((created_at <= last_reply_at)),
    CONSTRAINT dw1_pages_createdat_unwantedat__c_lt CHECK ((created_at <= unwanted_at)),
    CONSTRAINT dw1_pages_embpageurl__c_len CHECK (((length((embedding_page_url)::text) >= 1) AND (length((embedding_page_url)::text) <= 200))),
    CONSTRAINT dw1_pages_embpageurl__c_trim CHECK ((btrim((embedding_page_url)::text) = (embedding_page_url)::text)),
    CONSTRAINT dw1_pages_frequentposter1234__c_null CHECK ((((((last_reply_by_id IS NOT NULL) OR (frequent_poster_1_id IS NULL)) AND ((frequent_poster_1_id IS NOT NULL) OR (frequent_poster_2_id IS NULL))) AND ((frequent_poster_2_id IS NOT NULL) OR (frequent_poster_3_id IS NULL))) AND ((frequent_poster_3_id IS NOT NULL) OR (frequent_poster_4_id IS NULL)))),
    CONSTRAINT dw1_pages_htmlheaddescr__c_len CHECK (((length((html_head_description)::text) >= 1) AND (length((html_head_description)::text) <= 1000))),
    CONSTRAINT dw1_pages_htmlheadtitle__c_len CHECK (((length((html_head_title)::text) >= 1) AND (length((html_head_title)::text) <= 200))),
    CONSTRAINT dw1_pages_htmltagcssclass__c_len CHECK (((length((html_tag_css_classes)::text) >= 1) AND (length((html_tag_css_classes)::text) <= 100))),
    CONSTRAINT dw1_pages_htmltagcssclass__c_ptrn CHECK (is_valid_css_class(html_tag_css_classes)),
    CONSTRAINT dw1_pages_lastreplyat_byid__c_nn CHECK (((last_reply_at IS NULL) = (last_reply_by_id IS NULL))),
    CONSTRAINT dw1_pages_pagerole__c_in CHECK (((page_role >= 1) AND (page_role <= 30))),
    CONSTRAINT dw1_pages_pinorder_where__c_n CHECK (((pin_where IS NULL) = (pin_order IS NULL))),
    CONSTRAINT dw1_pages_pinwhere__c_in CHECK (((pin_where IS NULL) OR ((pin_where >= 1) AND (pin_where <= 3)))),
    CONSTRAINT dw1_pages_plannedat_doneat__c_le CHECK ((planned_at <= done_at)),
    CONSTRAINT dw1_pages_plannedat_doneat__c_null CHECK (((done_at IS NULL) OR (planned_at IS NOT NULL))),
    CONSTRAINT dw1_pages_publdat_bumpedat__c_le CHECK ((published_at <= bumped_at)),
    CONSTRAINT dw1_pages_role_answered__c CHECK (((page_role = 10) OR ((answered_at IS NULL) AND (answer_post_id IS NULL)))),
    CONSTRAINT dw1_pages_role_planned_done__c CHECK (((page_role = ANY (ARRAY[13, 14, 15])) OR ((planned_at IS NULL) AND (done_at IS NULL)))),
    CONSTRAINT dw1_pages_version__c_gz CHECK ((version >= 1))
);


--
-- Name: dw1_posts_read_stats; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_posts_read_stats (
    site_id character varying(32) NOT NULL,
    page_id character varying(32) NOT NULL,
    post_nr integer NOT NULL,
    ip character varying(39),
    user_id integer,
    read_at timestamp without time zone NOT NULL
);


--
-- Name: dw1_role_page_settings; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_role_page_settings (
    site_id character varying NOT NULL,
    role_id integer NOT NULL,
    page_id character varying NOT NULL,
    notf_level character varying NOT NULL,
    CONSTRAINT dw1_ropgst_notflevel__c_in CHECK (((notf_level)::text = ANY (ARRAY[('W'::character varying)::text, ('T'::character varying)::text, ('R'::character varying)::text, ('M'::character varying)::text])))
);


--
-- Name: dw1_settings; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_settings (
    site_id character varying NOT NULL,
    target character varying NOT NULL,
    page_id character varying,
    name character varying NOT NULL,
    datatype character varying NOT NULL,
    text_value character varying,
    long_value bigint,
    double_value double precision,
    CONSTRAINT dw1_stngs_datatype__c CHECK (
CASE datatype
    WHEN 'Text'::text THEN (((text_value IS NOT NULL) AND (long_value IS NULL)) AND (double_value IS NULL))
    WHEN 'Long'::text THEN (((long_value IS NOT NULL) AND (text_value IS NULL)) AND (double_value IS NULL))
    WHEN 'Double'::text THEN (((double_value IS NOT NULL) AND (text_value IS NULL)) AND (long_value IS NULL))
    WHEN 'Bool'::text THEN ((((text_value)::text = ANY (ARRAY[('T'::character varying)::text, ('F'::character varying)::text])) AND (long_value IS NULL)) AND (double_value IS NULL))
    ELSE NULL::boolean
END),
    CONSTRAINT dw1_stngs_name__c CHECK ((((length((name)::text) >= 1) AND (length((name)::text) <= 50)) AND (btrim((name)::text) = (name)::text))),
    CONSTRAINT dw1_stngs_textvalue__c_len CHECK ((length((text_value)::text) < (10 * 1000))),
    CONSTRAINT dw1_stngs_trgt__c_fks CHECK (
CASE target
    WHEN 'WholeSite'::text THEN (page_id IS NULL)
    WHEN 'PageTree'::text THEN (page_id IS NOT NULL)
    WHEN 'SinglePage'::text THEN (page_id IS NOT NULL)
    ELSE false
END)
);


--
-- Name: dw1_tenant_hosts; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_tenant_hosts (
    site_id character varying(32) NOT NULL,
    host character varying NOT NULL,
    canonical character varying(1) NOT NULL,
    ctime timestamp without time zone DEFAULT now() NOT NULL,
    mtime timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT dw1_hosts_host__c_len CHECK (((length((host)::text) >= 1) AND (length((host)::text) <= 100))),
    CONSTRAINT dw1_tnthsts_cncl__c CHECK (((canonical)::text = ANY (ARRAY[('C'::character varying)::text, ('R'::character varying)::text, ('L'::character varying)::text, ('D'::character varying)::text])))
);


--
-- Name: dw1_tenants; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_tenants (
    id character varying(32) NOT NULL,
    name character varying(100) NOT NULL,
    ctime timestamp without time zone DEFAULT now() NOT NULL,
    creator_ip character varying(39) NOT NULL,
    embedding_site_url character varying,
    next_page_id integer DEFAULT 1 NOT NULL,
    creator_email_address character varying NOT NULL,
    quota_limit_mbs integer,
    num_guests integer DEFAULT 0 NOT NULL,
    num_identities integer DEFAULT 0 NOT NULL,
    num_roles integer DEFAULT 0 NOT NULL,
    num_role_settings integer DEFAULT 0 NOT NULL,
    num_pages integer DEFAULT 0 NOT NULL,
    num_posts integer DEFAULT 0 NOT NULL,
    num_post_text_bytes bigint DEFAULT 0 NOT NULL,
    num_posts_read bigint DEFAULT 0 NOT NULL,
    num_actions integer DEFAULT 0 NOT NULL,
    num_notfs integer DEFAULT 0 NOT NULL,
    num_emails_sent integer DEFAULT 0 NOT NULL,
    num_audit_rows integer DEFAULT 0 NOT NULL,
    num_uploads integer DEFAULT 0 NOT NULL,
    num_upload_bytes bigint DEFAULT 0 NOT NULL,
    price_plan character varying,
    version integer DEFAULT 1 NOT NULL,
    num_post_revisions integer DEFAULT 0 NOT NULL,
    num_post_rev_bytes bigint DEFAULT 0 NOT NULL,
    CONSTRAINT dw1_sites_version__c_gz CHECK ((version >= 1)),
    CONSTRAINT dw1_tnt_creatoremail__c CHECK (((creator_email_address)::text ~~ '%@%.%'::text)),
    CONSTRAINT dw1_tnt_embsiteurl__c_len CHECK (((length((embedding_site_url)::text) >= 1) AND (length((embedding_site_url)::text) <= 100))),
    CONSTRAINT dw1_tnt_embsiteurl__c_trim CHECK ((btrim((embedding_site_url)::text) = (embedding_site_url)::text)),
    CONSTRAINT dw1_tnt_id__c_n0 CHECK (((id)::text <> '0'::text)),
    CONSTRAINT dw1_tnt_id__c_ne CHECK ((btrim((id)::text) <> ''::text)),
    CONSTRAINT dw1_tnt_name__c_len CHECK (((length((name)::text) >= 1) AND (length((name)::text) <= 100))),
    CONSTRAINT dw1_tnt_name__c_trim CHECK ((btrim((name)::text) = (name)::text)),
    CONSTRAINT dw1_tnt_name_embsiteurl__c CHECK (((name IS NOT NULL) OR (embedding_site_url IS NOT NULL))),
    CONSTRAINT dw1_tnt_priceplan__c_len CHECK ((length((price_plan)::text) < 100)),
    CONSTRAINT dw1_tnt_priceplan__c_ne CHECK ((length(btrim((price_plan)::text)) > 0))
);


--
-- Name: dw1_tenants_id; Type: SEQUENCE; Schema: public; Owner: debiki_dev
--

CREATE SEQUENCE dw1_tenants_id
    START WITH 10
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dw1_users; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw1_users (
    site_id character varying(32) NOT NULL,
    user_id integer NOT NULL,
    display_name character varying(100),
    email character varying(100),
    country character varying(100),
    website character varying(100),
    is_admin character varying(1),
    email_notfs character varying(1),
    is_owner character varying(1),
    username character varying,
    email_verified_at timestamp without time zone,
    created_at timestamp without time zone,
    password_hash character varying,
    email_for_every_new_post boolean,
    guest_cookie character varying,
    is_approved boolean,
    approved_at timestamp without time zone,
    approved_by_id integer,
    suspended_at timestamp without time zone,
    suspended_till timestamp without time zone,
    suspended_by_id integer,
    suspended_reason character varying,
    updated_at timestamp without time zone,
    is_moderator boolean,
    is_editor boolean,
    avatar_tiny_base_url character varying,
    avatar_tiny_hash_path character varying,
    avatar_small_base_url character varying,
    avatar_small_hash_path character varying,
    avatar_medium_base_url character varying,
    avatar_medium_hash_path character varying,
    CONSTRAINT dw1_users_approved__c_null CHECK ((((approved_by_id IS NULL) = (approved_at IS NULL)) AND ((is_approved IS NULL) OR (approved_by_id IS NOT NULL)))),
    CONSTRAINT dw1_users_auth__c_notnulls CHECK (((user_id < (-1)) OR (((created_at IS NOT NULL) AND (username IS NOT NULL)) AND (email_for_every_new_post IS NOT NULL)))),
    CONSTRAINT dw1_users_auth__c_nulls CHECK (((user_id < (-1)) OR (guest_cookie IS NULL))),
    CONSTRAINT dw1_users_avatarmediumbaseurl__c_len CHECK (((length((avatar_medium_base_url)::text) >= 1) AND (length((avatar_medium_base_url)::text) <= 100))),
    CONSTRAINT dw1_users_avatarmediumhashpath__c CHECK (is_valid_hash_path(avatar_medium_hash_path)),
    CONSTRAINT dw1_users_avatars__c CHECK (((user_id > 0) OR (avatar_tiny_base_url IS NULL))),
    CONSTRAINT dw1_users_avatars_none_or_all__c CHECK ((((((((avatar_tiny_base_url IS NULL) AND (avatar_tiny_hash_path IS NULL)) AND (avatar_small_base_url IS NULL)) AND (avatar_small_hash_path IS NULL)) AND (avatar_medium_base_url IS NULL)) AND (avatar_medium_hash_path IS NULL)) OR ((((((avatar_tiny_base_url IS NOT NULL) AND (avatar_tiny_hash_path IS NOT NULL)) AND (avatar_small_base_url IS NOT NULL)) AND (avatar_small_hash_path IS NOT NULL)) AND (avatar_medium_base_url IS NOT NULL)) AND (avatar_medium_hash_path IS NOT NULL)))),
    CONSTRAINT dw1_users_avatarsmallbaseurl__c_len CHECK (((length((avatar_small_base_url)::text) >= 1) AND (length((avatar_small_base_url)::text) <= 100))),
    CONSTRAINT dw1_users_avatarsmallhashpath__c CHECK (is_valid_hash_path(avatar_small_hash_path)),
    CONSTRAINT dw1_users_avatartinybaseurl__c_len CHECK (((length((avatar_tiny_base_url)::text) >= 1) AND (length((avatar_tiny_base_url)::text) <= 100))),
    CONSTRAINT dw1_users_avatartinyhashpath__c CHECK (is_valid_hash_path(avatar_tiny_hash_path)),
    CONSTRAINT dw1_users_country__c CHECK (((country)::text <> ''::text)),
    CONSTRAINT dw1_users_dname__c CHECK (((display_name)::text <> ''::text)),
    CONSTRAINT dw1_users_email__c CHECK ((((email)::text ~~ '%@%.%'::text) OR (user_id < (-1)))),
    CONSTRAINT dw1_users_emlntf__c CHECK (((email_notfs)::text = ANY (ARRAY[('R'::character varying)::text, ('N'::character varying)::text, ('F'::character varying)::text]))),
    CONSTRAINT dw1_users_guest__c_nn CHECK (((user_id >= (-1)) OR ((((created_at IS NOT NULL) AND (display_name IS NOT NULL)) AND (email IS NOT NULL)) AND (guest_cookie IS NOT NULL)))),
    CONSTRAINT dw1_users_guest__c_nulls CHECK (((user_id >= (-1)) OR (((((((((((((((((is_approved IS NULL) AND (approved_at IS NULL)) AND (approved_by_id IS NULL)) AND (suspended_at IS NULL)) AND (suspended_till IS NULL)) AND (suspended_by_id IS NULL)) AND (country IS NULL)) AND (website IS NULL)) AND (is_owner IS NULL)) AND (is_admin IS NULL)) AND (is_moderator IS NULL)) AND (is_editor IS NULL)) AND (username IS NULL)) AND (email_notfs IS NULL)) AND (email_verified_at IS NULL)) AND (password_hash IS NULL)) AND (email_for_every_new_post IS NULL)))),
    CONSTRAINT dw1_users_guestcookie__c_len CHECK ((length((guest_cookie)::text) < 30)),
    CONSTRAINT dw1_users_id__c CHECK (((user_id < 0) OR (100 <= user_id))),
    CONSTRAINT dw1_users_isowner__c_b CHECK (((is_owner)::text = 'T'::text)),
    CONSTRAINT dw1_users_passwordhash__c_len CHECK (((length((password_hash)::text) >= 8) AND (length((password_hash)::text) <= 150))),
    CONSTRAINT dw1_users_superadm__c CHECK (((is_admin)::text = 'T'::text)),
    CONSTRAINT dw1_users_suspended__c_null CHECK (((((suspended_by_id IS NULL) = (suspended_at IS NULL)) AND ((suspended_by_id IS NULL) = (suspended_till IS NULL))) AND ((suspended_by_id IS NULL) = (suspended_reason IS NULL)))),
    CONSTRAINT dw1_users_suspreason__c_len CHECK ((length((suspended_reason)::text) <= 255)),
    CONSTRAINT dw1_users_username__c_at CHECK (((username)::text !~~ '%@%'::text)),
    CONSTRAINT dw1_users_username__c_len CHECK ((length(btrim((username)::text)) >= 2)),
    CONSTRAINT dw1_users_username__c_len2 CHECK ((length((username)::text) < 40)),
    CONSTRAINT dw1_users_website__c CHECK (((website)::text <> ''::text))
);


--
-- Name: dw2_audit_log; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_audit_log (
    site_id character varying NOT NULL,
    audit_id bigint NOT NULL,
    doer_id integer NOT NULL,
    done_at timestamp without time zone NOT NULL,
    did_what smallint NOT NULL,
    details character varying,
    ip inet,
    browser_id_cookie character varying,
    browser_fingerprint integer,
    anonymity_network character varying,
    country character varying,
    region character varying,
    city character varying,
    page_id character varying,
    page_role smallint,
    post_id integer,
    post_nr integer,
    post_action_type integer,
    post_action_sub_id integer,
    target_page_id character varying,
    target_post_id integer,
    target_post_nr integer,
    target_user_id integer,
    target_site_id character varying,
    size_bytes integer,
    upload_hash_path character varying,
    upload_file_name character varying,
    email_address character varying,
    batch_id bigint,
    CONSTRAINT dw2_auditlog_batchid_btwn_1_id__c CHECK (((batch_id >= 1) AND (batch_id <= audit_id))),
    CONSTRAINT dw2_auditlog_didwhat__c_in CHECK (((did_what >= 1) AND (did_what <= 200))),
    CONSTRAINT dw2_auditlog_emailaddr__c_email CHECK (((email_address)::text ~~ '%_@_%'::text)),
    CONSTRAINT dw2_auditlog_emailaddr__c_len CHECK (((length((email_address)::text) >= 3) AND (length((email_address)::text) <= 200))),
    CONSTRAINT dw2_auditlog_hashpath__c CHECK (is_valid_hash_path(upload_hash_path)),
    CONSTRAINT dw2_auditlog_hashpathsuffix__c_len CHECK (((length((upload_hash_path)::text) >= 1) AND (length((upload_hash_path)::text) <= 100))),
    CONSTRAINT dw2_auditlog_page_post__c CHECK (((post_nr IS NULL) OR (page_id IS NOT NULL))),
    CONSTRAINT dw2_auditlog_pagerole__c_in CHECK (((page_role >= 1) AND (page_role <= 100))),
    CONSTRAINT dw2_auditlog_pagerole_pageid__c CHECK (((page_role IS NULL) OR (page_id IS NOT NULL))),
    CONSTRAINT dw2_auditlog_post__c CHECK (((post_nr IS NULL) = (post_id IS NULL))),
    CONSTRAINT dw2_auditlog_postaction__c CHECK (((post_action_type IS NULL) = (post_action_sub_id IS NULL))),
    CONSTRAINT dw2_auditlog_postaction__c2 CHECK (((post_action_type IS NULL) OR (post_id IS NOT NULL))),
    CONSTRAINT dw2_auditlog_size__c_gez CHECK ((size_bytes >= 0)),
    CONSTRAINT dw2_auditlog_tgtpost__c CHECK (((target_post_nr IS NULL) = (target_post_id IS NULL))),
    CONSTRAINT dw2_auditlog_uploadfilename__c CHECK (((upload_file_name)::text !~~ '%/%'::text)),
    CONSTRAINT dw2_auditlog_uploadfilename__c_len CHECK (((length((upload_file_name)::text) >= 1) AND (length((upload_file_name)::text) <= 200)))
);


--
-- Name: dw2_blocks; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_blocks (
    site_id character varying NOT NULL,
    block_type character varying,
    blocked_at timestamp without time zone NOT NULL,
    blocked_till timestamp without time zone,
    blocked_by_id integer NOT NULL,
    ip inet,
    browser_id_cookie character varying,
    CONSTRAINT dw2_blocks__c_something_blocked CHECK (((browser_id_cookie IS NOT NULL) OR (ip IS NOT NULL))),
    CONSTRAINT dw2_blocks_blockedat_till__c CHECK ((blocked_at <= blocked_till))
);


--
-- Name: dw2_categories; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_categories (
    site_id character varying NOT NULL,
    id integer NOT NULL,
    page_id character varying NOT NULL,
    parent_id integer,
    name character varying NOT NULL,
    slug character varying NOT NULL,
    "position" integer NOT NULL,
    description character varying,
    new_topic_types character varying,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    locked_at timestamp without time zone,
    frozen_at timestamp without time zone,
    deleted_at timestamp without time zone,
    hide_in_forum boolean DEFAULT false NOT NULL,
    CONSTRAINT dw2_cats_created_deleted__c_le CHECK ((created_at <= deleted_at)),
    CONSTRAINT dw2_cats_created_frozen__c_le CHECK ((created_at <= frozen_at)),
    CONSTRAINT dw2_cats_created_locked__c_le CHECK ((created_at <= locked_at)),
    CONSTRAINT dw2_cats_created_updated__c_le CHECK ((created_at <= updated_at)),
    CONSTRAINT dw2_cats_description__c_len CHECK ((length((description)::text) < 1000)),
    CONSTRAINT dw2_cats_name__c_len CHECK (((length((name)::text) >= 1) AND (length((name)::text) <= 100))),
    CONSTRAINT dw2_cats_newtopictypes__c CHECK (((new_topic_types)::text ~ '^([0-9]+,)*[0-9]+$'::text)),
    CONSTRAINT dw2_cats_slug__c_len CHECK (((length((slug)::text) >= 1) AND (length((slug)::text) <= 100)))
);


--
-- Name: dw2_invites; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_invites (
    site_id character varying NOT NULL,
    secret_key character varying NOT NULL,
    email_address character varying NOT NULL,
    created_by_id integer NOT NULL,
    created_at timestamp without time zone NOT NULL,
    accepted_at timestamp without time zone,
    user_id integer,
    deleted_at timestamp without time zone,
    deleted_by_id integer,
    invalidated_at timestamp without time zone,
    CONSTRAINT dw2_invites_accepted_user__c CHECK (((accepted_at IS NULL) = (user_id IS NULL))),
    CONSTRAINT dw2_invites_deleted__c CHECK (((deleted_at IS NULL) = (deleted_by_id IS NULL))),
    CONSTRAINT dw2_invites_deleted__c2 CHECK (((deleted_at IS NULL) OR (accepted_at IS NULL))),
    CONSTRAINT dw2_invites_deleted__c3 CHECK ((deleted_at >= created_at)),
    CONSTRAINT dw2_invites_email__c CHECK ((((email_address)::text ~~ '%@%'::text) AND (length((email_address)::text) >= 3))),
    CONSTRAINT dw2_invites_invalidated__c CHECK ((invalidated_at >= created_at)),
    CONSTRAINT dw2_invites_invalidated__c2 CHECK (((invalidated_at IS NULL) OR (accepted_at IS NULL))),
    CONSTRAINT dw2_invites_invalidated_deleted__c CHECK (((invalidated_at IS NULL) OR (deleted_at IS NULL))),
    CONSTRAINT dw2_invites_secretkey__c_len CHECK ((length((secret_key)::text) > 20))
);


--
-- Name: dw2_page_html; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_page_html (
    site_id character varying NOT NULL,
    page_id character varying NOT NULL,
    site_version integer NOT NULL,
    page_version integer NOT NULL,
    app_version character varying NOT NULL,
    data_hash character varying NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    html text NOT NULL
);


--
-- Name: dw2_post_actions; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_post_actions (
    site_id character varying NOT NULL,
    action_id integer,
    unique_post_id integer NOT NULL,
    page_id character varying NOT NULL,
    post_nr integer NOT NULL,
    type smallint NOT NULL,
    sub_id smallint NOT NULL,
    created_by_id integer NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone,
    deleted_at timestamp without time zone,
    deleted_by_id integer,
    CONSTRAINT dw2_postacs__c_delat_by CHECK (((deleted_at IS NULL) = (deleted_by_id IS NULL))),
    CONSTRAINT dw2_postacs__c_delat_ge_creat CHECK ((deleted_at >= created_at)),
    CONSTRAINT dw2_postacs__c_type_in CHECK ((type = ANY (ARRAY[31, 32, 41, 42, 43, 44, 51, 52, 53]))),
    CONSTRAINT dw2_postacs__c_updat_ge_creat CHECK ((updated_at >= created_at)),
    CONSTRAINT dw2_postacs__c_updat_ge_delat CHECK ((updated_at >= deleted_at))
);


--
-- Name: dw2_post_revisions; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_post_revisions (
    site_id character varying NOT NULL,
    post_id integer NOT NULL,
    revision_nr integer NOT NULL,
    previous_nr integer,
    source_patch text,
    full_source text,
    title character varying,
    composed_at timestamp without time zone NOT NULL,
    composed_by_id integer NOT NULL,
    approved_at timestamp without time zone,
    approved_by_id integer,
    hidden_at timestamp without time zone,
    hidden_by_id integer,
    CONSTRAINT dw2_postrevs_approved__c_null CHECK (((approved_at IS NULL) = (approved_by_id IS NULL))),
    CONSTRAINT dw2_postrevs_approvedat_ge_composedat__c CHECK ((approved_at >= composed_at)),
    CONSTRAINT dw2_postrevs_hidden__c_null CHECK (((hidden_at IS NULL) = (hidden_by_id IS NULL))),
    CONSTRAINT dw2_postrevs_hiddenat_ge_composedat__c CHECK ((hidden_at >= composed_at)),
    CONSTRAINT dw2_postrevs_patch_source__c_nn CHECK (((source_patch IS NOT NULL) OR (full_source IS NOT NULL))),
    CONSTRAINT dw2_postrevs_revisionnr_gt_prevnr__c CHECK ((revision_nr > previous_nr)),
    CONSTRAINT dw2_postrevs_revisionnr_prevnr__c_gz CHECK (((revision_nr > 0) AND (previous_nr > 0)))
);


--
-- Name: dw2_posts; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_posts (
    site_id character varying NOT NULL,
    unique_post_id integer NOT NULL,
    page_id character varying NOT NULL,
    post_nr integer NOT NULL,
    parent_nr integer,
    multireply character varying,
    created_at timestamp without time zone NOT NULL,
    created_by_id integer NOT NULL,
    curr_rev_started_at timestamp without time zone NOT NULL,
    curr_rev_last_edited_at timestamp without time zone,
    curr_rev_by_id integer NOT NULL,
    last_approved_edit_at timestamp without time zone,
    last_approved_edit_by_id integer,
    num_distinct_editors integer NOT NULL,
    num_edit_suggestions smallint DEFAULT 0 NOT NULL,
    last_edit_suggestion_at timestamp without time zone,
    safe_rev_nr integer,
    approved_source text,
    approved_html_sanitized text,
    approved_at timestamp without time zone,
    approved_by_id integer,
    approved_rev_nr integer,
    curr_rev_source_patch text,
    curr_rev_nr integer NOT NULL,
    collapsed_status smallint NOT NULL,
    collapsed_at timestamp without time zone,
    collapsed_by_id integer,
    closed_status smallint NOT NULL,
    closed_at timestamp without time zone,
    closed_by_id integer,
    hidden_at timestamp without time zone,
    hidden_by_id integer,
    hidden_reason character varying,
    deleted_status smallint NOT NULL,
    deleted_at timestamp without time zone,
    deleted_by_id integer,
    pinned_position smallint,
    pinned_at timestamp without time zone,
    pinned_by_id integer,
    num_pending_flags smallint DEFAULT 0 NOT NULL,
    num_handled_flags smallint DEFAULT 0 NOT NULL,
    num_like_votes integer DEFAULT 0 NOT NULL,
    num_wrong_votes integer DEFAULT 0 NOT NULL,
    num_times_read integer DEFAULT 0 NOT NULL,
    num_bury_votes integer DEFAULT 0 NOT NULL,
    num_unwanted_votes integer DEFAULT 0 NOT NULL,
    type smallint,
    prev_rev_nr integer,
    CONSTRAINT dw2_posts__c_approved CHECK (((((approved_rev_nr IS NULL) = (approved_at IS NULL)) AND ((approved_rev_nr IS NULL) = (approved_by_id IS NULL))) AND ((approved_rev_nr IS NULL) = (approved_source IS NULL)))),
    CONSTRAINT dw2_posts__c_apr_at_ge_cre CHECK (((approved_at IS NULL) OR (approved_at >= created_at))),
    CONSTRAINT dw2_posts__c_apr_html_ne CHECK (((approved_html_sanitized IS NULL) OR (length(btrim(approved_html_sanitized)) >= 1))),
    CONSTRAINT dw2_posts__c_apr_html_src CHECK (((approved_html_sanitized IS NULL) OR (approved_source IS NOT NULL))),
    CONSTRAINT dw2_posts__c_apr_src_ne CHECK (((approved_source IS NULL) OR (length(btrim(approved_source)) >= 1))),
    CONSTRAINT dw2_posts__c_apr_ver_le_cur CHECK (((approved_rev_nr IS NULL) OR (approved_rev_nr <= curr_rev_nr))),
    CONSTRAINT dw2_posts__c_closed CHECK (((((closed_at IS NULL) OR (closed_at >= created_at)) AND ((closed_status = 0) = (closed_at IS NULL))) AND ((closed_status = 0) = (closed_by_id IS NULL)))),
    CONSTRAINT dw2_posts__c_collapsed CHECK (((((collapsed_at IS NULL) OR (collapsed_at >= created_at)) AND ((collapsed_status = 0) = (collapsed_at IS NULL))) AND ((collapsed_status = 0) = (collapsed_by_id IS NULL)))),
    CONSTRAINT dw2_posts__c_counts_gez CHECK ((((((((((num_distinct_editors >= 0) AND (num_edit_suggestions >= 0)) AND (num_pending_flags >= 0)) AND (num_handled_flags >= 0)) AND (num_like_votes >= 0)) AND (num_wrong_votes >= 0)) AND (num_bury_votes >= 0)) AND (num_unwanted_votes >= 0)) AND (num_times_read >= 0))),
    CONSTRAINT dw2_posts__c_curpatch_ne CHECK (((curr_rev_source_patch IS NULL) OR (length(btrim(curr_rev_source_patch)) >= 1))),
    CONSTRAINT dw2_posts__c_deleted CHECK (((((deleted_at IS NULL) OR (deleted_at >= created_at)) AND ((deleted_status = 0) = (deleted_at IS NULL))) AND ((deleted_status = 0) = (deleted_by_id IS NULL)))),
    CONSTRAINT dw2_posts__c_first_rev_by_creator CHECK (((curr_rev_by_id = created_by_id) OR (curr_rev_nr > 1))),
    CONSTRAINT dw2_posts__c_first_rev_started_when_created CHECK (((curr_rev_started_at = created_at) OR (curr_rev_nr > 1))),
    CONSTRAINT dw2_posts__c_hidden CHECK (((((hidden_at IS NULL) OR (hidden_at >= created_at)) AND ((hidden_at IS NULL) = (hidden_by_id IS NULL))) AND ((hidden_reason IS NULL) OR (hidden_at IS NOT NULL)))),
    CONSTRAINT dw2_posts__c_last_apr_edit_at_id CHECK (((last_approved_edit_at IS NULL) = (last_approved_edit_by_id IS NULL))),
    CONSTRAINT dw2_posts__c_last_edi_sug CHECK ((((num_edit_suggestions = 0) OR (last_edit_suggestion_at IS NOT NULL)) AND ((last_edit_suggestion_at IS NULL) OR (last_edit_suggestion_at >= created_at)))),
    CONSTRAINT dw2_posts__c_ne CHECK (((approved_source IS NOT NULL) OR (curr_rev_source_patch IS NOT NULL))),
    CONSTRAINT dw2_posts__c_not_its_parent CHECK (((parent_nr IS NULL) OR (post_nr <> parent_nr))),
    CONSTRAINT dw2_posts__c_saf_ver_le_apr CHECK (((safe_rev_nr IS NULL) OR ((safe_rev_nr <= approved_rev_nr) AND (approved_rev_nr IS NOT NULL)))),
    CONSTRAINT dw2_posts__c_up_to_date_no_patch CHECK (((approved_rev_nr IS NULL) OR ((curr_rev_nr = approved_rev_nr) = (curr_rev_source_patch IS NULL)))),
    CONSTRAINT dw2_posts_curreveditedat_ge_lastapprovededitat__c CHECK ((curr_rev_last_edited_at >= last_approved_edit_at)),
    CONSTRAINT dw2_posts_curreveditedat_ge_startedat__c CHECK ((curr_rev_last_edited_at >= curr_rev_started_at)),
    CONSTRAINT dw2_posts_currevisionat_ge_createdat__c CHECK (((curr_rev_started_at IS NULL) OR (curr_rev_started_at >= created_at))),
    CONSTRAINT dw2_posts_multireply__c_num CHECK (((multireply)::text ~ '^([0-9]+,)*[0-9]+$'::text)),
    CONSTRAINT dw2_posts_parent__c_not_title CHECK ((parent_nr <> 0)),
    CONSTRAINT dw2_posts_type__c_in CHECK (((type >= 1) AND (type <= 100)))
);


--
-- Name: dw2_review_tasks; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_review_tasks (
    site_id character varying NOT NULL,
    id integer NOT NULL,
    reasons bigint NOT NULL,
    caused_by_id integer NOT NULL,
    created_at timestamp without time zone NOT NULL,
    created_at_rev_nr integer,
    more_reasons_at timestamp without time zone,
    completed_at timestamp without time zone,
    completed_at_rev_nr integer,
    completed_by_id integer,
    invalidated_at timestamp without time zone,
    resolution integer,
    user_id integer,
    page_id character varying,
    post_id integer,
    post_nr integer,
    CONSTRAINT dw2_reviewtasks_completed_or_invalidatedat_null__c CHECK (((completed_at IS NULL) OR (invalidated_at IS NULL))),
    CONSTRAINT dw2_reviewtasks_completedat_atrevnr__c_nn CHECK (((completed_at IS NOT NULL) OR (completed_at_rev_nr IS NULL))),
    CONSTRAINT dw2_reviewtasks_completedat_by__c_nn CHECK (((completed_at IS NULL) = (completed_by_id IS NULL))),
    CONSTRAINT dw2_reviewtasks_completedat_ge_createdat__c CHECK ((completed_at >= created_at)),
    CONSTRAINT dw2_reviewtasks_completedat_ge_morereasonsat__c CHECK ((completed_at >= more_reasons_at)),
    CONSTRAINT dw2_reviewtasks_invalidatedat_ge_createdat__c CHECK ((invalidated_at >= created_at)),
    CONSTRAINT dw2_reviewtasks_invalidatedat_ge_morereasonsat__c CHECK ((invalidated_at >= more_reasons_at)),
    CONSTRAINT dw2_reviewtasks_morereasonsat_ge_createdat__c CHECK ((more_reasons_at >= created_at)),
    CONSTRAINT dw2_reviewtasks_postid_nr__c_n CHECK (((post_id IS NULL) = (post_nr IS NULL))),
    CONSTRAINT dw2_reviewtasks_resolution__c_n CHECK ((((completed_by_id IS NULL) AND (invalidated_at IS NULL)) = (resolution IS NULL))),
    CONSTRAINT dw2_reviewtasks_thing__c_nn CHECK ((((post_id IS NOT NULL) OR (user_id IS NOT NULL)) OR (page_id IS NOT NULL)))
);


--
-- Name: dw2_upload_refs; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_upload_refs (
    site_id character varying NOT NULL,
    post_id integer NOT NULL,
    base_url character varying NOT NULL,
    hash_path character varying NOT NULL,
    added_by_id integer NOT NULL,
    added_at timestamp without time zone NOT NULL,
    CONSTRAINT dw2_uploadrefs_baseurl__c_len CHECK (((length((base_url)::text) >= 1) AND (length((base_url)::text) <= 100))),
    CONSTRAINT dw2_uploadrefs_hashpath__c CHECK (is_valid_hash_path(hash_path)),
    CONSTRAINT dw2_uploadrefs_hashpathsuffix__c_len CHECK (((length((hash_path)::text) >= 1) AND (length((hash_path)::text) <= 100)))
);


--
-- Name: dw2_uploads; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE dw2_uploads (
    base_url character varying NOT NULL,
    hash_path character varying NOT NULL,
    original_hash_path character varying NOT NULL,
    size_bytes integer NOT NULL,
    mime_type character varying NOT NULL,
    width integer,
    height integer,
    uploaded_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    num_references integer NOT NULL,
    verified_present_at timestamp without time zone,
    verified_absent_at timestamp without time zone,
    unused_since timestamp without time zone,
    CONSTRAINT dw2_uploads_0refs_unusedsince__c CHECK (((num_references = 0) = (unused_since IS NOT NULL))),
    CONSTRAINT dw2_uploads__c_dates CHECK (((verified_present_at > uploaded_at) AND (verified_absent_at > uploaded_at))),
    CONSTRAINT dw2_uploads__c_numbers CHECK (((((num_references >= 0) AND (size_bytes > 0)) AND (width > 0)) AND (height > 0))),
    CONSTRAINT dw2_uploads_baseurl__c CHECK (((base_url)::text ~~ '%/'::text)),
    CONSTRAINT dw2_uploads_baseurl__c_len CHECK (((length((base_url)::text) >= 1) AND (length((base_url)::text) <= 100))),
    CONSTRAINT dw2_uploads_hashpath__c CHECK (is_valid_hash_path(hash_path)),
    CONSTRAINT dw2_uploads_hashpathsuffix__c_len CHECK (((length((hash_path)::text) >= 1) AND (length((hash_path)::text) <= 100))),
    CONSTRAINT dw2_uploads_mimetype__c_len CHECK (((length((mime_type)::text) >= 1) AND (length((mime_type)::text) <= 100))),
    CONSTRAINT dw2_uploads_orighashpathsuffix__c_len CHECK (((length((original_hash_path)::text) >= 1) AND (length((original_hash_path)::text) <= 100))),
    CONSTRAINT dw2_uploads_originalhashpath__c CHECK (is_valid_hash_path(original_hash_path))
);


--
-- Name: message_members_3; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE message_members_3 (
    site_id character varying NOT NULL,
    page_id character varying NOT NULL,
    user_id integer NOT NULL,
    added_by_id integer NOT NULL,
    added_at timestamp without time zone NOT NULL
);


--
-- Name: schema_version; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE schema_version (
    version_rank integer NOT NULL,
    installed_rank integer NOT NULL,
    version character varying(50) NOT NULL,
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: settings_3; Type: TABLE; Schema: public; Owner: debiki_dev
--

CREATE TABLE settings_3 (
    site_id character varying NOT NULL,
    category_id integer,
    page_id character varying,
    user_must_be_auth boolean,
    user_must_be_approved boolean,
    allow_guest_login boolean,
    num_first_posts_to_review smallint,
    num_first_posts_to_approve smallint,
    num_first_posts_to_allow smallint,
    head_styles_html character varying,
    head_scripts_html character varying,
    end_of_body_html character varying,
    header_html character varying,
    footer_html character varying,
    show_forum_categories boolean,
    horizontal_comments boolean,
    social_links_html character varying,
    logo_url_or_html character varying,
    org_domain character varying,
    org_full_name character varying,
    org_short_name character varying,
    contrib_agreement smallint,
    content_license smallint,
    google_analytics_id character varying,
    experimental boolean,
    many_sections boolean,
    html_tag_css_classes character varying,
    CONSTRAINT settings3_auth_guest__c CHECK ((NOT (allow_guest_login AND (user_must_be_auth OR user_must_be_approved)))),
    CONSTRAINT settings3_contentlicense__c_in CHECK (((content_license >= 1) AND (content_license <= 100))),
    CONSTRAINT settings3_contrib_agr_and_license__c_null CHECK ((((contrib_agreement IS NULL) OR (contrib_agreement = 10)) OR ((content_license IS NOT NULL) AND (content_license = contrib_agreement)))),
    CONSTRAINT settings3_contribagr__c_in CHECK (((contrib_agreement >= 1) AND (contrib_agreement <= 100))),
    CONSTRAINT settings3_endofbodyhtml__c_len CHECK (((length((end_of_body_html)::text) >= 1) AND (length((end_of_body_html)::text) <= 20000))),
    CONSTRAINT settings3_footerhtml__c_len CHECK (((length((footer_html)::text) >= 1) AND (length((footer_html)::text) <= 20000))),
    CONSTRAINT settings3_googleanalyticsid__c_len CHECK (((length((google_analytics_id)::text) >= 1) AND (length((google_analytics_id)::text) <= 100))),
    CONSTRAINT settings3_headerhtml__c_len CHECK (((length((header_html)::text) >= 1) AND (length((header_html)::text) <= 20000))),
    CONSTRAINT settings3_headscriptshtml__c_len CHECK (((length((head_scripts_html)::text) >= 1) AND (length((head_scripts_html)::text) <= 20000))),
    CONSTRAINT settings3_headstyleshtml__c_len CHECK (((length((head_styles_html)::text) >= 1) AND (length((head_styles_html)::text) <= 20000))),
    CONSTRAINT settings3_htmltagcssclasses__c_len CHECK (((length((html_tag_css_classes)::text) >= 1) AND (length((html_tag_css_classes)::text) <= 100))),
    CONSTRAINT settings3_htmltagcssclasses__c_valid CHECK (is_valid_css_class(html_tag_css_classes)),
    CONSTRAINT settings3_logourlorhtml__c_len CHECK (((length((logo_url_or_html)::text) >= 1) AND (length((logo_url_or_html)::text) <= 10000))),
    CONSTRAINT settings3_numfirst_allow_ge_approve CHECK ((num_first_posts_to_allow >= num_first_posts_to_approve)),
    CONSTRAINT settings3_numfirsttoallow_0_to_10 CHECK (((num_first_posts_to_allow >= 0) AND (num_first_posts_to_allow <= 10))),
    CONSTRAINT settings3_numfirsttoapprove_0_to_10 CHECK (((num_first_posts_to_approve >= 0) AND (num_first_posts_to_approve <= 10))),
    CONSTRAINT settings3_numfirsttoreview_0_to_10 CHECK (((num_first_posts_to_review >= 0) AND (num_first_posts_to_review <= 10))),
    CONSTRAINT settings3_only_for_site__c CHECK ((((category_id IS NULL) AND (page_id IS NULL)) OR ((((((((((((((user_must_be_auth IS NULL) AND (user_must_be_approved IS NULL)) AND (allow_guest_login IS NULL)) AND (num_first_posts_to_review IS NULL)) AND (num_first_posts_to_approve IS NULL)) AND (num_first_posts_to_allow IS NULL)) AND (org_domain IS NULL)) AND (org_full_name IS NULL)) AND (org_short_name IS NULL)) AND (contrib_agreement IS NULL)) AND (content_license IS NULL)) AND (google_analytics_id IS NULL)) AND (experimental IS NULL)) AND (many_sections IS NULL)))),
    CONSTRAINT settings3_orgdomain__c_len CHECK (((length((org_domain)::text) >= 1) AND (length((org_domain)::text) <= 100))),
    CONSTRAINT settings3_orgfullname__c_len CHECK (((length((org_full_name)::text) >= 1) AND (length((org_full_name)::text) <= 100))),
    CONSTRAINT settings3_orgfullname__c_trim CHECK ((btrim((org_full_name)::text) = (org_full_name)::text)),
    CONSTRAINT settings3_orgshortname__c_len CHECK (((length((org_short_name)::text) >= 1) AND (length((org_short_name)::text) <= 100))),
    CONSTRAINT settings3_page_or_cat_null__c CHECK (((category_id IS NULL) OR (page_id IS NULL))),
    CONSTRAINT settings3_required_for_site__c CHECK ((((category_id IS NOT NULL) OR (page_id IS NOT NULL)) OR (org_full_name IS NOT NULL))),
    CONSTRAINT settings3_sociallinkshtml__c_len CHECK (((length((social_links_html)::text) >= 1) AND (length((social_links_html)::text) <= 10000)))
);


--
-- Name: dw1_emlot_tnt_id__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_emails_out
    ADD CONSTRAINT dw1_emlot_tnt_id__p PRIMARY KEY (site_id, id);


--
-- Name: dw1_ids_siteid_id__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_identities
    ADD CONSTRAINT dw1_ids_siteid_id__p PRIMARY KEY (site_id, id);


--
-- Name: dw1_idsmpleml__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_guest_prefs
    ADD CONSTRAINT dw1_idsmpleml__p PRIMARY KEY (site_id, email, ctime);


--
-- Name: dw1_idsoid_tnt_oid__u; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_identities
    ADD CONSTRAINT dw1_idsoid_tnt_oid__u UNIQUE (site_id, oid_claimed_id);


--
-- Name: dw1_notfs_id__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_notfs_id__p PRIMARY KEY (site_id, notf_id);


--
-- Name: dw1_pages__u; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages__u UNIQUE (site_id, page_id);


--
-- Name: dw1_ropgst_site_role_page__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_role_page_settings
    ADD CONSTRAINT dw1_ropgst_site_role_page__p PRIMARY KEY (site_id, role_id, page_id);


--
-- Name: dw1_stngs_tnt_trgt_page_name__u; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_settings
    ADD CONSTRAINT dw1_stngs_tnt_trgt_page_name__u UNIQUE (site_id, target, page_id, name);


--
-- Name: dw1_tenants_id__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_tenants
    ADD CONSTRAINT dw1_tenants_id__p PRIMARY KEY (id);


--
-- Name: dw1_tenants_name__u; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_tenants
    ADD CONSTRAINT dw1_tenants_name__u UNIQUE (name);


--
-- Name: dw1_tnthsts_host__u; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_tenant_hosts
    ADD CONSTRAINT dw1_tnthsts_host__u UNIQUE (host);


--
-- Name: dw1_users_tnt_sno__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_users
    ADD CONSTRAINT dw1_users_tnt_sno__p PRIMARY KEY (site_id, user_id);


--
-- Name: dw2_auditlog__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_audit_log
    ADD CONSTRAINT dw2_auditlog__p PRIMARY KEY (site_id, audit_id);


--
-- Name: dw2_cats_id__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_categories
    ADD CONSTRAINT dw2_cats_id__p PRIMARY KEY (site_id, id);


--
-- Name: dw2_invites__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_invites
    ADD CONSTRAINT dw2_invites__p PRIMARY KEY (site_id, secret_key);


--
-- Name: dw2_pagehtml__pageid; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_page_html
    ADD CONSTRAINT dw2_pagehtml__pageid PRIMARY KEY (site_id, page_id);


--
-- Name: dw2_postacs__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_actions
    ADD CONSTRAINT dw2_postacs__p PRIMARY KEY (site_id, unique_post_id, type, created_by_id, sub_id);


--
-- Name: dw2_postrevs_postid_revnr__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_revisions
    ADD CONSTRAINT dw2_postrevs_postid_revnr__p PRIMARY KEY (site_id, post_id, revision_nr);


--
-- Name: dw2_posts_id__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_id__p PRIMARY KEY (site_id, unique_post_id);


--
-- Name: dw2_reviewtasks__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_review_tasks
    ADD CONSTRAINT dw2_reviewtasks__p PRIMARY KEY (site_id, id);


--
-- Name: dw2_uploadrefs__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_upload_refs
    ADD CONSTRAINT dw2_uploadrefs__p PRIMARY KEY (site_id, post_id, base_url, hash_path);


--
-- Name: dw2_uploads__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_uploads
    ADD CONSTRAINT dw2_uploads__p PRIMARY KEY (base_url, hash_path);


--
-- Name: msgmbr3_page_user__p; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY message_members_3
    ADD CONSTRAINT msgmbr3_page_user__p PRIMARY KEY (site_id, page_id, user_id);


--
-- Name: schema_version_pk; Type: CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY schema_version
    ADD CONSTRAINT schema_version_pk PRIMARY KEY (version);


--
-- Name: dw1_ids_securesocial; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_ids_securesocial ON dw1_identities USING btree (site_id, securesocial_provider_id, securesocial_user_id);


--
-- Name: dw1_idsmpleml_version__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_idsmpleml_version__u ON dw1_guest_prefs USING btree (site_id, email, version) WHERE (version = 'C'::bpchar);


--
-- Name: dw1_idsoid_email; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_idsoid_email ON dw1_identities USING btree (email);


--
-- Name: dw1_idsoid_tnt_email__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_idsoid_tnt_email__u ON dw1_identities USING btree (site_id, email) WHERE ((oid_endpoint)::text = 'https://www.google.com/accounts/o8/ud'::text);


--
-- Name: dw1_idsoid_tnt_usr; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_idsoid_tnt_usr ON dw1_identities USING btree (site_id, user_id);


--
-- Name: dw1_ntfs_createdat_email_undecided__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_ntfs_createdat_email_undecided__i ON dw1_notifications USING btree (created_at) WHERE (email_status = 1);


--
-- Name: dw1_ntfs_emailid; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_ntfs_emailid ON dw1_notifications USING btree (site_id, email_id);


--
-- Name: dw1_ntfs_postid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_ntfs_postid__i ON dw1_notifications USING btree (site_id, unique_post_id) WHERE (unique_post_id IS NOT NULL);


--
-- Name: dw1_ntfs_seen_createdat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_ntfs_seen_createdat__i ON dw1_notifications USING btree ((
CASE
    WHEN (seen_at IS NULL) THEN (created_at + '100 years'::interval)
    ELSE created_at
END) DESC);


--
-- Name: dw1_pages_bumpedat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_bumpedat__i ON dw1_pages USING btree (site_id, bumped_at DESC);


--
-- Name: dw1_pages_category__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_category__i ON dw1_pages USING btree (site_id, category_id);


--
-- Name: dw1_pages_category_about__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_pages_category_about__u ON dw1_pages USING btree (site_id, category_id, page_role) WHERE (page_role = 9);


--
-- Name: dw1_pages_frequentposter1id__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_frequentposter1id__i ON dw1_pages USING btree (site_id, frequent_poster_1_id) WHERE (frequent_poster_1_id IS NOT NULL);


--
-- Name: dw1_pages_frequentposter2id__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_frequentposter2id__i ON dw1_pages USING btree (site_id, frequent_poster_2_id) WHERE (frequent_poster_2_id IS NOT NULL);


--
-- Name: dw1_pages_frequentposter3id__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_frequentposter3id__i ON dw1_pages USING btree (site_id, frequent_poster_3_id) WHERE (frequent_poster_3_id IS NOT NULL);


--
-- Name: dw1_pages_frequentposter4id__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_frequentposter4id__i ON dw1_pages USING btree (site_id, frequent_poster_4_id) WHERE (frequent_poster_4_id IS NOT NULL);


--
-- Name: dw1_pages_lastreplybyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_lastreplybyid__i ON dw1_pages USING btree (site_id, last_reply_by_id) WHERE (last_reply_by_id IS NOT NULL);


--
-- Name: dw1_pages_likes_bump__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_likes_bump__i ON dw1_pages USING btree (site_id, num_likes DESC, bumped_at DESC);


--
-- Name: dw1_pages_pinorder__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_pinorder__i ON dw1_pages USING btree (site_id, pin_order) WHERE (pin_order IS NOT NULL);


--
-- Name: dw1_pages_publishedat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pages_publishedat__i ON dw1_pages USING btree (site_id, published_at);


--
-- Name: dw1_pgpths_path__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_pgpths_path__u ON dw1_page_paths USING btree (site_id, page_id, parent_folder, page_slug, show_id);


--
-- Name: dw1_pgpths_path_noid_cncl__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_pgpths_path_noid_cncl__u ON dw1_page_paths USING btree (site_id, parent_folder, page_slug) WHERE (((show_id)::text = 'F'::text) AND ((canonical)::text = 'C'::text));


--
-- Name: dw1_pgpths_tnt_fldr_slg_cncl; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pgpths_tnt_fldr_slg_cncl ON dw1_page_paths USING btree (site_id, parent_folder, page_slug, canonical);


--
-- Name: dw1_pgpths_tnt_pgid_cncl; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_pgpths_tnt_pgid_cncl ON dw1_page_paths USING btree (site_id, page_id, canonical);


--
-- Name: dw1_pgpths_tnt_pgid_cncl__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_pgpths_tnt_pgid_cncl__u ON dw1_page_paths USING btree (site_id, page_id) WHERE ((canonical)::text = 'C'::text);


--
-- Name: dw1_pstsrd_guest_ip__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_pstsrd_guest_ip__u ON dw1_posts_read_stats USING btree (site_id, page_id, post_nr, ip) WHERE ((user_id IS NULL) OR ((user_id)::text ~~ '-%'::text));


--
-- Name: dw1_pstsrd_role__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_pstsrd_role__u ON dw1_posts_read_stats USING btree (site_id, page_id, post_nr, user_id);


--
-- Name: dw1_ropgst_site_page; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_ropgst_site_page ON dw1_role_page_settings USING btree (site_id, page_id);


--
-- Name: dw1_stngs_tnt_trgt_name__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_stngs_tnt_trgt_name__u ON dw1_settings USING btree (site_id, target, name) WHERE (page_id IS NULL);


--
-- Name: dw1_tenants_creatoremail; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_tenants_creatoremail ON dw1_tenants USING btree (creator_email_address);


--
-- Name: dw1_tenants_creatorip; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_tenants_creatorip ON dw1_tenants USING btree (creator_ip);


--
-- Name: dw1_tnthsts_tnt_cncl__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_tnthsts_tnt_cncl__u ON dw1_tenant_hosts USING btree (site_id) WHERE ((canonical)::text = 'C'::text);


--
-- Name: dw1_user_guest__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_user_guest__u ON dw1_users USING btree (site_id, display_name, email, guest_cookie) WHERE (user_id < (-1));


--
-- Name: dw1_user_guestcookie__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_user_guestcookie__i ON dw1_users USING btree (site_id, guest_cookie) WHERE (user_id < (-1));


--
-- Name: dw1_user_guestemail__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_user_guestemail__i ON dw1_users USING btree (site_id, email) WHERE (user_id < (-1));


--
-- Name: dw1_users_approvedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_approvedbyid__i ON dw1_users USING btree (site_id, approved_by_id) WHERE (approved_by_id IS NOT NULL);


--
-- Name: dw1_users_avatarmediumbaseurl__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_avatarmediumbaseurl__i ON dw1_users USING btree (avatar_medium_base_url);


--
-- Name: dw1_users_avatarmediumhashpath__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_avatarmediumhashpath__i ON dw1_users USING btree (avatar_medium_hash_path);


--
-- Name: dw1_users_avatarsmallbaseurl__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_avatarsmallbaseurl__i ON dw1_users USING btree (avatar_small_base_url);


--
-- Name: dw1_users_avatarsmallhashpath__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_avatarsmallhashpath__i ON dw1_users USING btree (avatar_small_hash_path);


--
-- Name: dw1_users_avatartinybaseurl__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_avatartinybaseurl__i ON dw1_users USING btree (avatar_tiny_base_url);


--
-- Name: dw1_users_avatartinyhashpath__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_avatartinyhashpath__i ON dw1_users USING btree (avatar_tiny_hash_path);


--
-- Name: dw1_users_site_email__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_users_site_email__u ON dw1_users USING btree (site_id, email) WHERE (user_id >= (-1));


--
-- Name: dw1_users_site_usernamelower__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw1_users_site_usernamelower__u ON dw1_users USING btree (site_id, lower((username)::text));


--
-- Name: dw1_users_suspendebyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw1_users_suspendebyid__i ON dw1_users USING btree (site_id, suspended_by_id) WHERE (suspended_by_id IS NOT NULL);


--
-- Name: dw2_auditlog_doer_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_doer_doneat__i ON dw2_audit_log USING btree (site_id, doer_id, done_at);


--
-- Name: dw2_auditlog_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_doneat__i ON dw2_audit_log USING btree (site_id, done_at);


--
-- Name: dw2_auditlog_fingerprint_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_fingerprint_doneat__i ON dw2_audit_log USING btree (site_id, browser_fingerprint, done_at);


--
-- Name: dw2_auditlog_idcookie_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_idcookie_doneat__i ON dw2_audit_log USING btree (site_id, browser_id_cookie, done_at);


--
-- Name: dw2_auditlog_ip_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_ip_doneat__i ON dw2_audit_log USING btree (site_id, ip, done_at);


--
-- Name: dw2_auditlog_page_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_page_doneat__i ON dw2_audit_log USING btree (site_id, page_id, done_at) WHERE (page_id IS NOT NULL);


--
-- Name: dw2_auditlog_post_doneat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_post_doneat__i ON dw2_audit_log USING btree (site_id, post_id, done_at) WHERE (post_id IS NOT NULL);


--
-- Name: dw2_auditlog_uploadhashpathsuffix__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_auditlog_uploadhashpathsuffix__i ON dw2_audit_log USING btree (upload_hash_path) WHERE (upload_hash_path IS NOT NULL);


--
-- Name: dw2_blocks_blockedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_blocks_blockedby__i ON dw2_blocks USING btree (site_id, blocked_by_id);


--
-- Name: dw2_blocks_browseridcookie__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_blocks_browseridcookie__u ON dw2_blocks USING btree (site_id, browser_id_cookie) WHERE (browser_id_cookie IS NOT NULL);


--
-- Name: dw2_blocks_ip__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_blocks_ip__u ON dw2_blocks USING btree (site_id, ip) WHERE (ip IS NOT NULL);


--
-- Name: dw2_cats_page__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_cats_page__i ON dw2_categories USING btree (site_id, page_id);


--
-- Name: dw2_cats_page_slug__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_cats_page_slug__u ON dw2_categories USING btree (site_id, page_id, slug);


--
-- Name: dw2_cats_parent_slug__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_cats_parent_slug__u ON dw2_categories USING btree (site_id, parent_id, slug);


--
-- Name: dw2_cats_slug__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_cats_slug__i ON dw2_categories USING btree (site_id, slug);


--
-- Name: dw2_emlot_touser__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_emlot_touser__i ON dw1_emails_out USING btree (site_id, to_user_id);


--
-- Name: dw2_invites_createdby_at__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_invites_createdby_at__i ON dw2_invites USING btree (site_id, created_by_id, created_at);


--
-- Name: dw2_invites_deletedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_invites_deletedby__i ON dw2_invites USING btree (site_id, deleted_by_id) WHERE (deleted_by_id IS NOT NULL);


--
-- Name: dw2_invites_email__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_invites_email__u ON dw2_invites USING btree (site_id, email_address, created_by_id) WHERE ((deleted_at IS NULL) AND (invalidated_at IS NULL));


--
-- Name: dw2_invites_user__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_invites_user__i ON dw2_invites USING btree (site_id, user_id) WHERE (user_id IS NOT NULL);


--
-- Name: dw2_ntfs_touserid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_ntfs_touserid__i ON dw1_notifications USING btree (site_id, to_user_id);


--
-- Name: dw2_pages_createdby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_pages_createdby__i ON dw1_pages USING btree (site_id, author_id);


--
-- Name: dw2_postacs_createdby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postacs_createdby__i ON dw2_post_actions USING btree (site_id, created_by_id);


--
-- Name: dw2_postacs_deletedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postacs_deletedby__i ON dw2_post_actions USING btree (site_id, deleted_by_id) WHERE (deleted_by_id IS NOT NULL);


--
-- Name: dw2_postacs_page_byuser; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postacs_page_byuser ON dw2_post_actions USING btree (site_id, page_id, created_by_id);


--
-- Name: dw2_postrevs_approvedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postrevs_approvedby__i ON dw2_post_revisions USING btree (site_id, approved_by_id) WHERE (approved_by_id IS NOT NULL);


--
-- Name: dw2_postrevs_composedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postrevs_composedby__i ON dw2_post_revisions USING btree (site_id, composed_by_id);


--
-- Name: dw2_postrevs_hiddenby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postrevs_hiddenby__i ON dw2_post_revisions USING btree (site_id, hidden_by_id) WHERE (hidden_by_id IS NOT NULL);


--
-- Name: dw2_postrevs_postid_prevnr__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_postrevs_postid_prevnr__i ON dw2_post_revisions USING btree (site_id, post_id, previous_nr) WHERE (previous_nr IS NOT NULL);


--
-- Name: dw2_posts_approvedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_approvedbyid__i ON dw2_posts USING btree (site_id, approved_by_id) WHERE (approved_by_id IS NOT NULL);


--
-- Name: dw2_posts_closedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_closedbyid__i ON dw2_posts USING btree (site_id, closed_by_id) WHERE (closed_by_id IS NOT NULL);


--
-- Name: dw2_posts_collapsedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_collapsedbyid__i ON dw2_posts USING btree (site_id, collapsed_by_id) WHERE (collapsed_by_id IS NOT NULL);


--
-- Name: dw2_posts_createdby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_createdby__i ON dw2_posts USING btree (site_id, created_by_id);


--
-- Name: dw2_posts_deletedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_deletedbyid__i ON dw2_posts USING btree (site_id, deleted_by_id) WHERE (deleted_by_id IS NOT NULL);


--
-- Name: dw2_posts_hiddenbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_hiddenbyid__i ON dw2_posts USING btree (site_id, hidden_by_id) WHERE (hidden_by_id IS NOT NULL);


--
-- Name: dw2_posts_lastapprovededitbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_lastapprovededitbyid__i ON dw2_posts USING btree (site_id, last_approved_edit_by_id) WHERE (last_approved_edit_by_id IS NOT NULL);


--
-- Name: dw2_posts_lasteditedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_lasteditedbyid__i ON dw2_posts USING btree (site_id, curr_rev_by_id) WHERE (curr_rev_by_id IS NOT NULL);


--
-- Name: dw2_posts_numflags__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_numflags__i ON dw2_posts USING btree (site_id, num_pending_flags) WHERE ((deleted_status = 0) AND (num_pending_flags > 0));


--
-- Name: dw2_posts_page_parentnr__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_page_parentnr__i ON dw2_posts USING btree (site_id, page_id, parent_nr);


--
-- Name: dw2_posts_page_postnr__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_posts_page_postnr__u ON dw2_posts USING btree (site_id, page_id, post_nr);


--
-- Name: dw2_posts_pendingedits__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_pendingedits__i ON dw2_posts USING btree (site_id, last_edit_suggestion_at) WHERE ((((deleted_status = 0) AND (num_pending_flags = 0)) AND (approved_rev_nr = curr_rev_nr)) AND (num_edit_suggestions > 0));


--
-- Name: dw2_posts_pinnedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_pinnedbyid__i ON dw2_posts USING btree (site_id, pinned_by_id) WHERE (pinned_by_id IS NOT NULL);


--
-- Name: dw2_posts_unapproved__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_posts_unapproved__i ON dw2_posts USING btree (site_id, curr_rev_last_edited_at) WHERE (((deleted_status = 0) AND (num_pending_flags = 0)) AND ((approved_rev_nr IS NULL) OR (approved_rev_nr < curr_rev_nr)));


--
-- Name: dw2_reviewtasks_causedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_causedbyid__i ON dw2_review_tasks USING btree (site_id, caused_by_id);


--
-- Name: dw2_reviewtasks_completedbyid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_completedbyid__i ON dw2_review_tasks USING btree (site_id, completed_by_id) WHERE (completed_by_id IS NOT NULL);


--
-- Name: dw2_reviewtasks_createdat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_createdat__i ON dw2_review_tasks USING btree (site_id, created_at DESC);


--
-- Name: dw2_reviewtasks_open_causedby_postid__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_reviewtasks_open_causedby_postid__u ON dw2_review_tasks USING btree (site_id, caused_by_id, post_id) WHERE ((post_id IS NOT NULL) AND (resolution IS NULL));


--
-- Name: dw2_reviewtasks_open_causedby_userid__u; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX dw2_reviewtasks_open_causedby_userid__u ON dw2_review_tasks USING btree (site_id, caused_by_id, user_id) WHERE ((user_id IS NOT NULL) AND (resolution IS NULL));


--
-- Name: dw2_reviewtasks_open_createdat__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_open_createdat__i ON dw2_review_tasks USING btree (site_id, created_at DESC) WHERE (resolution IS NULL);


--
-- Name: dw2_reviewtasks_pageid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_pageid__i ON dw2_review_tasks USING btree (site_id, page_id) WHERE (page_id IS NOT NULL);


--
-- Name: dw2_reviewtasks_postid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_postid__i ON dw2_review_tasks USING btree (site_id, post_id) WHERE (post_id IS NOT NULL);


--
-- Name: dw2_reviewtasks_userid__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_reviewtasks_userid__i ON dw2_review_tasks USING btree (site_id, user_id) WHERE (user_id IS NOT NULL);


--
-- Name: dw2_uploadrefs_addedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_uploadrefs_addedby__i ON dw2_upload_refs USING btree (site_id, added_by_id);


--
-- Name: dw2_uploadrefs_baseurl__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_uploadrefs_baseurl__i ON dw2_upload_refs USING btree (base_url);


--
-- Name: dw2_uploadrefs_hashpathsuffix__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_uploadrefs_hashpathsuffix__i ON dw2_upload_refs USING btree (hash_path);


--
-- Name: dw2_uploads_hashpathsuffix__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_uploads_hashpathsuffix__i ON dw2_uploads USING btree (hash_path);


--
-- Name: dw2_uploads_unusedsince__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX dw2_uploads_unusedsince__i ON dw2_uploads USING btree (unused_since) WHERE (num_references = 0);


--
-- Name: msgmbr3_addedby__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX msgmbr3_addedby__i ON message_members_3 USING btree (site_id, added_by_id);


--
-- Name: msgmbr3_user__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX msgmbr3_user__i ON message_members_3 USING btree (site_id, user_id);


--
-- Name: schema_version_ir_idx; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX schema_version_ir_idx ON schema_version USING btree (installed_rank);


--
-- Name: schema_version_s_idx; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX schema_version_s_idx ON schema_version USING btree (success);


--
-- Name: schema_version_vr_idx; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX schema_version_vr_idx ON schema_version USING btree (version_rank);


--
-- Name: settings3_site__i; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE INDEX settings3_site__i ON settings_3 USING btree (site_id);


--
-- Name: settings3_site_category; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX settings3_site_category ON settings_3 USING btree (site_id, category_id) WHERE (category_id IS NOT NULL);


--
-- Name: settings3_site_page; Type: INDEX; Schema: public; Owner: debiki_dev
--

CREATE UNIQUE INDEX settings3_site_page ON settings_3 USING btree (site_id, page_id) WHERE (page_id IS NOT NULL);


--
-- Name: dw1_pstsrd_ignore_dupl_ins; Type: RULE; Schema: public; Owner: debiki_dev
--

CREATE RULE dw1_pstsrd_ignore_dupl_ins AS
    ON INSERT TO dw1_posts_read_stats
   WHERE (EXISTS ( SELECT 1
           FROM dw1_posts_read_stats
          WHERE (((((dw1_posts_read_stats.site_id)::text = (new.site_id)::text) AND ((dw1_posts_read_stats.page_id)::text = (new.page_id)::text)) AND (dw1_posts_read_stats.post_nr = new.post_nr)) AND ((dw1_posts_read_stats.user_id = new.user_id) OR ((dw1_posts_read_stats.ip)::text = (new.ip)::text))))) DO INSTEAD NOTHING;


--
-- Name: dw1_emails_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_emails_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_emails_out FOR EACH ROW EXECUTE PROCEDURE dw1_emails_summary();


--
-- Name: dw1_identities_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_identities_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_identities FOR EACH ROW EXECUTE PROCEDURE dw1_identities_summary();


--
-- Name: dw1_notfs_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_notfs_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_notifications FOR EACH ROW EXECUTE PROCEDURE dw1_notfs_summary();


--
-- Name: dw1_pages_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_pages_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_pages FOR EACH ROW EXECUTE PROCEDURE dw1_pages_summary();


--
-- Name: dw1_posts_read_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_posts_read_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_posts_read_stats FOR EACH ROW EXECUTE PROCEDURE dw1_posts_read_summary();


--
-- Name: dw1_role_page_settings_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_role_page_settings_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_role_page_settings FOR EACH ROW EXECUTE PROCEDURE dw1_role_page_settings_summary();


--
-- Name: dw1_roles_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw1_roles_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_users FOR EACH ROW EXECUTE PROCEDURE dw1_roles_summary();


--
-- Name: dw2_actions_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw2_actions_summary AFTER INSERT OR DELETE OR UPDATE ON dw2_post_actions FOR EACH ROW EXECUTE PROCEDURE dw2_post_actions_summary();


--
-- Name: dw2_posts_summary; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER dw2_posts_summary AFTER INSERT OR DELETE OR UPDATE ON dw2_posts FOR EACH ROW EXECUTE PROCEDURE dw2_posts_summary();


--
-- Name: sum_post_revs_quota_3; Type: TRIGGER; Schema: public; Owner: debiki_dev
--

CREATE TRIGGER sum_post_revs_quota_3 AFTER INSERT OR DELETE OR UPDATE ON dw2_post_revisions FOR EACH ROW EXECUTE PROCEDURE sum_post_revs_quota_3();


--
-- Name: dw1_emlot__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_emails_out
    ADD CONSTRAINT dw1_emlot__r__users FOREIGN KEY (site_id, to_user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_ids_userid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_identities
    ADD CONSTRAINT dw1_ids_userid__r__users FOREIGN KEY (site_id, user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_ids_useridorig__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_identities
    ADD CONSTRAINT dw1_ids_useridorig__r__users FOREIGN KEY (site_id, user_id_orig) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_ntfs__r__emails; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs__r__emails FOREIGN KEY (site_id, email_id) REFERENCES dw1_emails_out(site_id, id);


--
-- Name: dw1_ntfs__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw1_ntfs__r__postacs; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs__r__postacs FOREIGN KEY (site_id, unique_post_id, action_type, by_user_id, action_sub_id) REFERENCES dw2_post_actions(site_id, unique_post_id, type, created_by_id, sub_id);


--
-- Name: dw1_ntfs__r__sites; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs__r__sites FOREIGN KEY (site_id) REFERENCES dw1_tenants(id);


--
-- Name: dw1_ntfs_byuserid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs_byuserid__r__users FOREIGN KEY (site_id, by_user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_ntfs_postid__r__posts; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs_postid__r__posts FOREIGN KEY (site_id, unique_post_id) REFERENCES dw2_posts(site_id, unique_post_id);


--
-- Name: dw1_ntfs_touserid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_notifications
    ADD CONSTRAINT dw1_ntfs_touserid__r__users FOREIGN KEY (site_id, to_user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pages__r__tenant; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages__r__tenant FOREIGN KEY (site_id) REFERENCES dw1_tenants(id) DEFERRABLE;


--
-- Name: dw1_pages_category__r__categories; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_category__r__categories FOREIGN KEY (site_id, category_id) REFERENCES dw2_categories(site_id, id) DEFERRABLE;


--
-- Name: dw1_pages_createdbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_createdbyid__r__users FOREIGN KEY (site_id, author_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pages_frequentposter1id__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_frequentposter1id__r__users FOREIGN KEY (site_id, frequent_poster_1_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pages_frequentposter2id__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_frequentposter2id__r__users FOREIGN KEY (site_id, frequent_poster_2_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pages_frequentposter3id__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_frequentposter3id__r__users FOREIGN KEY (site_id, frequent_poster_3_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pages_frequentposter4id__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_frequentposter4id__r__users FOREIGN KEY (site_id, frequent_poster_4_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pages_lastreplybyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_pages
    ADD CONSTRAINT dw1_pages_lastreplybyid__r__users FOREIGN KEY (site_id, last_reply_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_pgpths_tnt_pgid__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_page_paths
    ADD CONSTRAINT dw1_pgpths_tnt_pgid__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id) DEFERRABLE;


--
-- Name: dw1_pstsrd__r__posts; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_posts_read_stats
    ADD CONSTRAINT dw1_pstsrd__r__posts FOREIGN KEY (site_id, page_id, post_nr) REFERENCES dw2_posts(site_id, page_id, post_nr) DEFERRABLE;


--
-- Name: dw1_pstsrd__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_posts_read_stats
    ADD CONSTRAINT dw1_pstsrd__r__users FOREIGN KEY (site_id, user_id) REFERENCES dw1_users(site_id, user_id) DEFERRABLE;


--
-- Name: dw1_ropgst__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_role_page_settings
    ADD CONSTRAINT dw1_ropgst__r__users FOREIGN KEY (site_id, role_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_ropgst_site_page__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_role_page_settings
    ADD CONSTRAINT dw1_ropgst_site_page__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw1_stngs_pageid__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_settings
    ADD CONSTRAINT dw1_stngs_pageid__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw1_tnthsts__r__tenants; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_tenant_hosts
    ADD CONSTRAINT dw1_tnthsts__r__tenants FOREIGN KEY (site_id) REFERENCES dw1_tenants(id);


--
-- Name: dw1_users__r__tenant; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_users
    ADD CONSTRAINT dw1_users__r__tenant FOREIGN KEY (site_id) REFERENCES dw1_tenants(id) DEFERRABLE;


--
-- Name: dw1_users_approvedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_users
    ADD CONSTRAINT dw1_users_approvedbyid__r__users FOREIGN KEY (site_id, approved_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw1_users_suspendebyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw1_users
    ADD CONSTRAINT dw1_users_suspendebyid__r__users FOREIGN KEY (site_id, suspended_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_auditlog__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_audit_log
    ADD CONSTRAINT dw2_auditlog__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw2_auditlog__r__posts; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_audit_log
    ADD CONSTRAINT dw2_auditlog__r__posts FOREIGN KEY (site_id, post_id) REFERENCES dw2_posts(site_id, unique_post_id);


--
-- Name: dw2_auditlog_doer__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_audit_log
    ADD CONSTRAINT dw2_auditlog_doer__r__users FOREIGN KEY (site_id, doer_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_auditlog_targetuser__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_audit_log
    ADD CONSTRAINT dw2_auditlog_targetuser__r__users FOREIGN KEY (site_id, target_user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_auditlog_tgtsite__r__sites; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_audit_log
    ADD CONSTRAINT dw2_auditlog_tgtsite__r__sites FOREIGN KEY (target_site_id) REFERENCES dw1_tenants(id);


--
-- Name: dw2_blocks_blockedby__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_blocks
    ADD CONSTRAINT dw2_blocks_blockedby__r__users FOREIGN KEY (site_id, blocked_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_cats__r__cats; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_categories
    ADD CONSTRAINT dw2_cats__r__cats FOREIGN KEY (site_id, parent_id) REFERENCES dw2_categories(site_id, id);


--
-- Name: dw2_cats_page__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_categories
    ADD CONSTRAINT dw2_cats_page__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id) DEFERRABLE;


--
-- Name: dw2_invites_inviter__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_invites
    ADD CONSTRAINT dw2_invites_inviter__r__users FOREIGN KEY (site_id, created_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_invites_user__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_invites
    ADD CONSTRAINT dw2_invites_user__r__users FOREIGN KEY (site_id, user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_pagehtml__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_page_html
    ADD CONSTRAINT dw2_pagehtml__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw2_postacs__r__posts; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_actions
    ADD CONSTRAINT dw2_postacs__r__posts FOREIGN KEY (site_id, unique_post_id) REFERENCES dw2_posts(site_id, unique_post_id);


--
-- Name: dw2_postacs_createdbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_actions
    ADD CONSTRAINT dw2_postacs_createdbyid__r__users FOREIGN KEY (site_id, created_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_postacs_deletedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_actions
    ADD CONSTRAINT dw2_postacs_deletedbyid__r__users FOREIGN KEY (site_id, deleted_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_postrevs_approvedby__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_revisions
    ADD CONSTRAINT dw2_postrevs_approvedby__r__users FOREIGN KEY (site_id, approved_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_postrevs_composedby__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_revisions
    ADD CONSTRAINT dw2_postrevs_composedby__r__users FOREIGN KEY (site_id, composed_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_postrevs_hiddenby__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_revisions
    ADD CONSTRAINT dw2_postrevs_hiddenby__r__users FOREIGN KEY (site_id, hidden_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_postrevs_postid__r__posts; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_revisions
    ADD CONSTRAINT dw2_postrevs_postid__r__posts FOREIGN KEY (site_id, post_id) REFERENCES dw2_posts(site_id, unique_post_id);


--
-- Name: dw2_postrevs_prevnr_r__postrevs; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_post_revisions
    ADD CONSTRAINT dw2_postrevs_prevnr_r__postrevs FOREIGN KEY (site_id, post_id, previous_nr) REFERENCES dw2_post_revisions(site_id, post_id, revision_nr);


--
-- Name: dw2_posts__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw2_posts_approvedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_approvedbyid__r__users FOREIGN KEY (site_id, approved_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_closedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_closedbyid__r__users FOREIGN KEY (site_id, closed_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_collapsedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_collapsedbyid__r__users FOREIGN KEY (site_id, collapsed_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_createdbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_createdbyid__r__users FOREIGN KEY (site_id, created_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_deletedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_deletedbyid__r__users FOREIGN KEY (site_id, deleted_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_hiddenbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_hiddenbyid__r__users FOREIGN KEY (site_id, hidden_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_lastapprovededitbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_lastapprovededitbyid__r__users FOREIGN KEY (site_id, last_approved_edit_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_lasteditedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_lasteditedbyid__r__users FOREIGN KEY (site_id, curr_rev_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_posts_pinnedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_posts
    ADD CONSTRAINT dw2_posts_pinnedbyid__r__users FOREIGN KEY (site_id, pinned_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_reviewtasks__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_review_tasks
    ADD CONSTRAINT dw2_reviewtasks__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: dw2_reviewtasks__r__posts; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_review_tasks
    ADD CONSTRAINT dw2_reviewtasks__r__posts FOREIGN KEY (site_id, post_id) REFERENCES dw2_posts(site_id, unique_post_id);


--
-- Name: dw2_reviewtasks_causedbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_review_tasks
    ADD CONSTRAINT dw2_reviewtasks_causedbyid__r__users FOREIGN KEY (site_id, caused_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_reviewtasks_complbyid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_review_tasks
    ADD CONSTRAINT dw2_reviewtasks_complbyid__r__users FOREIGN KEY (site_id, completed_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_reviewtasks_userid__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_review_tasks
    ADD CONSTRAINT dw2_reviewtasks_userid__r__users FOREIGN KEY (site_id, user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: dw2_uploadrefs__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY dw2_upload_refs
    ADD CONSTRAINT dw2_uploadrefs__r__users FOREIGN KEY (site_id, added_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: msgmbr3__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY message_members_3
    ADD CONSTRAINT msgmbr3__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: msgmbr3_addedby__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY message_members_3
    ADD CONSTRAINT msgmbr3_addedby__r__users FOREIGN KEY (site_id, added_by_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: msgmbr3_user__r__users; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY message_members_3
    ADD CONSTRAINT msgmbr3_user__r__users FOREIGN KEY (site_id, user_id) REFERENCES dw1_users(site_id, user_id);


--
-- Name: settings3_cat__r__cats; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY settings_3
    ADD CONSTRAINT settings3_cat__r__cats FOREIGN KEY (site_id, category_id) REFERENCES dw2_categories(site_id, id);


--
-- Name: settings3_page__r__pages; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY settings_3
    ADD CONSTRAINT settings3_page__r__pages FOREIGN KEY (site_id, page_id) REFERENCES dw1_pages(site_id, page_id);


--
-- Name: settings3_site__r__sites; Type: FK CONSTRAINT; Schema: public; Owner: debiki_dev
--

ALTER TABLE ONLY settings_3
    ADD CONSTRAINT settings3_site__r__sites FOREIGN KEY (site_id) REFERENCES dw1_tenants(id);


SET SESSION AUTHORIZATION 'postgres';

--
-- Name: public; Type: ACL; Schema: -; Owner: postgres
--

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM postgres;
GRANT ALL ON SCHEMA public TO postgres;
GRANT ALL ON SCHEMA public TO PUBLIC;


--
-- PostgreSQL database dump complete
--

