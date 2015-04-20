
-- FK from dw1_posts_read_stats to dw2_posts:
alter table dw1_posts_read_stats drop constraint dw1_pstsrd_site_page__r__pages;
alter table dw1_posts_read_stats add constraint dw1_pstsrd__r__posts
  foreign key (site_id, page_id, post_id) references dw2_posts(site_id, page_id, post_id);


alter table dw1_tenants drop column num_action_text_bytes;

update dw1_tenants set num_posts = (
  select count(*) from dw2_posts where dw1_tenants.id = dw2_posts.site_id);

update dw1_tenants set num_post_text_bytes = (
  select coalesce(
    sum(coalesce(length(approved_source), 0)) +
    sum(coalesce(length(current_source_patch), 0)), 0)
  from dw2_posts where dw1_tenants.id = dw2_posts.site_id);

update dw1_tenants set num_posts_read = (
  select count(*) from dw1_posts_read_stats
  where dw1_tenants.id = dw1_posts_read_stats.site_id);

update dw1_tenants set num_actions = (
  select count(*) from dw2_post_actions
  where dw1_tenants.id = dw2_post_actions.site_id);

update dw1_tenants set num_notfs = (
  select count(*) from dw1_notifications
  where dw1_tenants.id = dw1_notifications.site_id);


drop trigger dw1_posts_summary on dw1_posts;
drop function dw1_posts_summary();

create or replace function dw2_posts_summary() returns trigger as $dw2_posts_summary$
    declare
        delta_rows integer;
        delta_text_bytes integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            delta_text_bytes =
                - coalesce(length(old.approved_source), 0)
                - coalesce(length(old.current_source_patch), 0);
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            delta_text_bytes =
                + coalesce(length(new.approved_source), 0)
                + coalesce(length(new.current_source_patch), 0)
                - coalesce(length(old.approved_source), 0)
                - coalesce(length(old.current_source_patch), 0);
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            delta_text_bytes =
                + coalesce(length(new.approved_source), 0)
                + coalesce(length(new.current_source_patch), 0);
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_posts = num_posts + delta_rows,
                num_post_text_bytes = num_post_text_bytes + delta_text_bytes
            where id = site_id;
        return null;
    end;
$dw2_posts_summary$ language plpgsql;

create trigger dw2_posts_summary
after insert or update or delete on dw2_posts
    for each row execute procedure dw2_posts_summary();


drop trigger dw1_actions_summary on dw1_page_actions;
drop function dw1_actions_summary();

create or replace function dw2_post_actions_summary() returns trigger as $dw2_post_actions_summary$
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
$dw2_post_actions_summary$ language plpgsql;

create trigger dw2_actions_summary
after insert or update or delete on dw2_post_actions
    for each row execute procedure dw2_post_actions_summary();
