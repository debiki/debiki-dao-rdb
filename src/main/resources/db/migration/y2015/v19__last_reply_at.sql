
alter table dw1_pages add last_reply_at timestamp;

-- Always specify bumped_at so one can sort by bumped_at in the forum topic list.
update dw1_pages set bumped_at = coalesce(published_at, created_at) where bumped_at is null;
alter table dw1_pages alter bumped_at set not null;

alter table dw1_pages add constraint dw1_pages_createdat_replyat__c_le check (created_at <= last_reply_at);
alter table dw1_pages add constraint dw1_pages_replyat_bumpedat__c_le check (last_reply_at <= bumped_at);
alter table dw1_pages add constraint dw1_pages_publdat_bumpedat__c_le check (published_at <= bumped_at);

