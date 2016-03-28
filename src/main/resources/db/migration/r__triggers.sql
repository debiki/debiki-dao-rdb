
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


CREATE TRIGGER dw1_emails_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_emails_out FOR EACH ROW EXECUTE PROCEDURE dw1_emails_summary();


CREATE TRIGGER dw1_identities_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_identities FOR EACH ROW EXECUTE PROCEDURE dw1_identities_summary();


CREATE TRIGGER dw1_notfs_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_notifications FOR EACH ROW EXECUTE PROCEDURE dw1_notfs_summary();


CREATE TRIGGER dw1_pages_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_pages FOR EACH ROW EXECUTE PROCEDURE dw1_pages_summary();


CREATE TRIGGER dw1_posts_read_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_posts_read_stats FOR EACH ROW EXECUTE PROCEDURE dw1_posts_read_summary();


CREATE TRIGGER dw1_role_page_settings_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_role_page_settings FOR EACH ROW EXECUTE PROCEDURE dw1_role_page_settings_summary();


CREATE TRIGGER dw1_roles_summary AFTER INSERT OR DELETE OR UPDATE ON dw1_users FOR EACH ROW EXECUTE PROCEDURE dw1_roles_summary();


CREATE TRIGGER dw2_actions_summary AFTER INSERT OR DELETE OR UPDATE ON dw2_post_actions FOR EACH ROW EXECUTE PROCEDURE dw2_post_actions_summary();


CREATE TRIGGER dw2_posts_summary AFTER INSERT OR DELETE OR UPDATE ON dw2_posts FOR EACH ROW EXECUTE PROCEDURE dw2_posts_summary();


CREATE TRIGGER sum_post_revs_quota_3 AFTER INSERT OR DELETE OR UPDATE ON dw2_post_revisions FOR EACH ROW EXECUTE PROCEDURE sum_post_revs_quota_3();


