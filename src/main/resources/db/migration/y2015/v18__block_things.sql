
create table dw2_blocked_things(
  site_id varchar not null,
  block_type varchar,
  blocked_at timestamp not null,
  blocked_till timestamp,
  blocked_by_id int not null,
  id_cookie varchar,
  ip inet,
  fingerprint int,
  country varchar,
  constraint dw2_blkdthngs_blockedby__r__users foreign key(site_id, blocked_by_id) references dw1_users(site_id, user_id),
  constraint dw2_blkdthngs_blockedat_till__c check(blocked_at <= blocked_till),
  constraint dw2_blkdthngs__c_something_blocked check(
      (case when id_cookie is null then 0 else 1 end) +
      (case when ip is null then 0 else 1 end) +
      (case when fingerprint is null then 0 else 1 end) +
      (case when country is null then 0 else 1 end)
         >= 1)
);

create unique index dw2_blkdthngs_ip__u on dw2_blocked_things(site_id, ip) where ip is not null;
create unique index dw2_blkdthngs_browseridcookie__u on dw2_blocked_things(site_id, id_cookie) where id_cookie is not null;
create index dw2_blkdthngs_blockedby__i on dw2_blocked_things(site_id, blocked_by_id);


create table dw2_audit_log(
  site_id varchar not null,
  audit_id int not null,
  doer_id int not null,
  done_at timestamp not null,
  did_what varchar not null,
  details varchar,
  ip inet,
  id_cookie varchar,
  fingerprint int,
  anonymity_network varchar,
  country varchar,
  region varchar,
  city varchar,
  page_id varchar,
  page_role varchar,
  post_id int,
  post_action_type int,
  post_action_sub_id int,
  target_post_id int,
  target_page_id varchar,
  target_user_id int,
  constraint dw2_auditlog__p primary key (site_id, audit_id),
  constraint dw2_auditlog_doer__r__users foreign key (site_id, doer_id) references dw1_users(site_id, user_id),
  constraint dw2_auditlog_targetuser__r__users foreign key (site_id, target_user_id) references dw1_users(site_id, user_id),
  constraint dw2_auditlog_post__r__posts foreign key (site_id, page_id, post_id) references dw2_posts(site_id, page_id, post_id),
  constraint dw2_auditlog_page_post__c check (post_id is null or page_id is not null),
  constraint dw2_auditlog_postaction__c check (post_action_type is null = post_action_sub_id is null)
);

create index dw2_auditlog_doneat on dw2_audit_log(site_id, done_at);
create index dw2_auditlog_doer_doneat on dw2_audit_log(site_id, doer_id, done_at);
create index dw2_auditlog_post_doneat on dw2_audit_log(site_id, page_id, post_id, done_at) where post_id is not null;
create index dw2_auditlog_page_doneat on dw2_audit_log(site_id, page_id, done_at) where page_id is not null;
create index dw2_auditlog_ip_doneat on dw2_audit_log(site_id, ip, done_at);
create index dw2_auditlog_brwsrid_doneat on dw2_audit_log(site_id, id_cookie, done_at);
create index dw2_auditlog_brwsrfp_doneat on dw2_audit_log(site_id, fingerprint, done_at);

