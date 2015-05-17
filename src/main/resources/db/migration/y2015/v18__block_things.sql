
create table dw2_blocked_things(
  site_id varchar not null,
  ip inet,
  browser_id_cookie varchar,
  block_type varchar,
  blocked_at timestamp not null,
  blocked_by_id int not null,
  blocked_till timestamp,
  constraint dw2_blkdthngs_blockedby__r__users foreign key(site_id, blocked_by_id) references dw1_users(site_id, user_id),
  constraint dw2_blkdthngs_blockedat_till__c check(blocked_at <= blocked_till),
  constraint dw2_blkdthngs__c_something_blocked check((ip is null) <> (browser_id_cookie is null))
);

create unique index dw2_blkdthngs_ip__u on dw2_blocked_things(site_id, ip) where ip is not null;
create unique index dw2_blkdthngs_browseridcookie__u on dw2_blocked_things(site_id, browser_id_cookie) where browser_id_cookie is not null;
create index dw2_blkdthngs_blockedby__i on dw2_blocked_things(site_id, blocked_by_id);

