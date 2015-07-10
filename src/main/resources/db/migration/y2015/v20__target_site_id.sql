
alter table dw2_audit_log add column target_site_id varchar;
alter table dw2_audit_log add constraint dw2_auditlog_tgtsite__r__sites
    foreign key (target_site_id) references dw1_tenants(id);

