
create table dw2_review_tasks(
  site_id varchar not null,
  id int not null,
  done_by_id int not null,
  reasons bigint not null,
  already_approved boolean,
  created_at timestamp not null,
  more_reasons_at timestamp,
  reviewed_at timestamp,
  reviewed_by_id int,
  invalidated_at timestamp,
  resolution int,
  user_id int,
  post_id int,
  revision_nr int,
  -- could also review:
  --page_id varchar,
  --category_id varchar,
  --upload_id varchar,
  --group_id int,
  constraint dw2_reviewtasks__p primary key (site_id, id),
  constraint dw2_reviewtasks_donebyid__r__users foreign key (site_id, done_by_id) references dw1_users(site_id, user_id), -- ix: dw2_reviewtasks_userid__i
  constraint dw2_reviewtasks_complbyid__r__users foreign key (site_id, reviewed_by_id) references dw1_users(site_id, user_id), -- ix: dw2_reviewtasks_userid__i
  constraint dw2_reviewtasks_userid__r__users foreign key (site_id, user_id) references dw1_users(site_id, user_id), -- ix: dw2_reviewtasks_userid__i
  constraint dw2_reviewtasks__r__posts foreign key (site_id, post_id) references dw2_posts(site_id, unique_post_id), -- ix: dw2_reviewtasks_postid__i
  constraint dw2_reviewtasks_morereasonsat_ge_createdat__c check(more_reasons_at >= created_at),
  constraint dw2_reviewtasks_reviewedat_ge_createdat__c check(reviewed_at >= created_at),
  constraint dw2_reviewtasks_reviewedat_ge_morereasonsat__c check(reviewed_at >= more_reasons_at),
  constraint dw2_reviewtasks_reviewedat_by__c_nn check(reviewed_at is null = reviewed_by_id is null), -- dw2_reviewtasks_reviewedbyid__i
  constraint dw2_reviewtasks_invalidatedat_ge_createdat__c check (invalidated_at >= created_at),
  constraint dw2_reviewtasks_invalidatedat_ge_morereasonsat__c check(invalidated_at >= more_reasons_at),
  constraint dw2_reviewtasks_reviewed_or_invalidatedat_null__c check (reviewed_at is null or invalidated_at is null),
  constraint dw2_reviewtasks_resolution__c_null check(
    reviewed_by_id is not null or invalidated_at is not null or resolution is null),
  constraint dw2_reviewtasks_resolution__c_gt_0 check(
    reviewed_by_id is null or (resolution is not null and resolution > 0)),
  constraint dw2_reviewtasks_resolution__c_lt_0 check(
    invalidated_at is null or (resolution is not null and resolution < 0))
);


create index dw2_reviewtasks_createdat__i on dw2_review_tasks(site_id, created_at desc);

create index dw2_reviewtasks_donebyid__i on dw2_review_tasks(site_id, done_by_id);

create index dw2_reviewtasks_reviewedbyid__i on dw2_review_tasks(site_id, reviewed_by_id)
    where reviewed_by_id is not null;

create index dw2_reviewtasks_userid__i on dw2_review_tasks(site_id, user_id);

-- Only one pending review task per user (no post).
create unique index dw2_reviewtasks_userid__u on dw2_review_tasks(site_id, user_id)
    where post_id is null and reviewed_at is null and invalidated_at is null;

create index dw2_reviewtasks_postid__i on dw2_review_tasks(site_id, post_id)
    where post_id is not null;

-- Only one pending review task per post.
create unique index dw2_reviewtasks_postid__u on dw2_review_tasks(site_id, post_id)
    where post_id is not null and reviewed_at is null and invalidated_at is null;

