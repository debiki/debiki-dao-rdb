
-- When a problem or idea was accepted / planned to be fixed/done.
alter table dw1_pages add column planned_at timestamp;

update dw1_pages set planned_at = created_at where done_at is not null;

alter table dw1_pages add constraint dw1_pages_createdat_plannedat__c_le check (created_at <= planned_at);
alter table dw1_pages add constraint dw1_pages_plannedat_doneat__c_le check (planned_at <= done_at);
alter table dw1_pages add constraint dw1_pages_plannedat_doneat__c_null check (
    done_at is null or planned_at is not null);

