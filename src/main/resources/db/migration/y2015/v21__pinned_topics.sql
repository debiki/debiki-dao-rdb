
alter table dw1_pages add column pin_order smallint;
alter table dw1_pages add column pin_where smallint;
alter table dw1_pages add constraint dw1_pages_pinorder_where__c_n check(
    pin_where is null = pin_order is null);
alter table dw1_pages add constraint dw1_pages_pinwhere__c_in check(
    pin_where is null or pin_where between 1 and 3);

create index dw1_pages_pinorder__i on dw1_pages(site_id, pin_order) where pin_order is not null;
create index dw1_pages_bumpedat__i on dw1_pages(site_id, bumped_at desc);
create index dw1_pages_likes_bump__i on dw1_pages(site_id, num_likes desc, bumped_at desc);

