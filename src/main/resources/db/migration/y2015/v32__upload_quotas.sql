
alter table dw1_tenants add column num_uploads integer default 0 not null;
alter table dw1_tenants add column num_upload_bytes bigint default 0 not null;

alter table dw1_tenants add column num_post_revisions integer default 0 not null;
alter table dw1_tenants add column num_post_rev_bytes bigint default 0 not null;


create or replace function sum_post_revs_quota_3() returns trigger as $sum_post_revs_quota_3$
    declare
        delta_rows integer;
        delta_bytes integer;
        site_id varchar;
    begin
        if (tg_op = 'DELETE') then
            delta_rows = -1;
            delta_bytes =
                - coalesce(length(old.source_patch), 0)
                - coalesce(length(old.full_source), 0),
                - coalesce(length(old.title), 0);
            site_id = old.site_id;
        elsif (tg_op = 'UPDATE') then
            delta_rows = 0;
            delta_bytes =
                + coalesce(length(new.source_patch), 0)
                + coalesce(length(new.full_source), 0)
                + coalesce(length(new.title), 0),
                - coalesce(length(old.source_patch), 0)
                - coalesce(length(old.full_source), 0),
                - coalesce(length(old.title), 0);
            site_id = new.site_id;
        elsif (tg_op = 'INSERT') then
            delta_rows = 1;
            delta_bytes =
                + coalesce(length(new.source_patch), 0)
                + coalesce(length(new.full_source), 0),
                + coalesce(length(new.title), 0);
            site_id = new.site_id;
        end if;
        update dw1_tenants
            set num_post_revisions = num_post_revisions + delta_rows,
                num_post_rev_bytes = num_post_rev_bytes + delta_bytes
            where id = site_id;
        return null;
    end;
$sum_post_revs_quota_3$ language plpgsql;

create trigger sum_post_revs_quota_3
after insert or update or delete on dw2_post_revisions
    for each row execute procedure sum_post_revs_quota_3();


update dw1_tenants set num_post_revisions = (
    select count(*) from dw2_post_revisions
    where dw1_tenants.id = dw2_post_revisions.site_id);

update dw1_tenants set num_post_rev_bytes = (
    select coalesce(
        sum(coalesce(length(source_patch), 0)) +
        sum(coalesce(length(full_source), 0)) +
        sum(coalesce(length(title), 0)),
        0)
    from dw2_post_revisions
    where dw1_tenants.id = dw2_post_revisions.site_id);


update dw1_tenants set num_uploads = (
    select count(*)
    from dw2_upload_refs r inner join dw2_uploads u
        on r.base_url = u.base_url and r.hash_path = u.hash_path
    where r.site_id = id);
update dw1_tenants set num_upload_bytes = (
    select coalesce(sum(u.size_bytes), 0)
    from dw2_upload_refs r inner join dw2_uploads u
        on r.base_url = u.base_url and r.hash_path = u.hash_path
    where r.site_id = id);

