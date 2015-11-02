
create table dw2_post_revisions(
  site_id varchar not null,
  post_id int not null,
  revision_nr int not null,
  previous_nr int,
  source_patch text,
  full_source text,
  title varchar,
  composed_at timestamp not null,
  composed_by_id int not null,
  approved_at timestamp,
  approved_by_id int,
  hidden_at timestamp,
  hidden_by_id int,
  constraint dw2_postrevs_postid_revnr__p primary key (site_id, post_id, revision_nr),
  constraint dw2_postrevs_postid__r__posts foreign key (site_id, post_id) references dw2_posts(site_id, unique_post_id),
  constraint dw2_postrevs_prevnr_r__postrevs foreign key (site_id, post_id, previous_nr) references dw2_post_revisions(site_id, post_id, revision_nr), -- ix: dw2_postrevs_postid_prevnr__i
  constraint dw2_postrevs_composedby__r__users foreign key (site_id, composed_by_id) references dw1_users(site_id, user_id), -- ix: dw2_postrevs_composedby__i
  constraint dw2_postrevs_approvedby__r__users foreign key (site_id, approved_by_id) references dw1_users(site_id, user_id), -- ix: dw2_postrevs_approvedby__i
  constraint dw2_postrevs_hiddenby__r__users foreign key (site_id, hidden_by_id) references dw1_users(site_id, user_id), -- ix: dw2_postrevs_hiddenby__i
  constraint dw2_postrevs_revisionnr_prevnr__c_gz check (revision_nr > 0 and previous_nr > 0),
  constraint dw2_postrevs_revisionnr_gt_prevnr__c check (revision_nr > previous_nr),
  constraint dw2_postrevs_patch_source__c_nn check (source_patch is not null or full_source is not null),
  constraint dw2_postrevs_approvedat_ge_composedat__c check (approved_at >= composed_at),
  constraint dw2_postrevs_approved__c_null check(approved_at is null = approved_by_id is null),
  constraint dw2_postrevs_hiddenat_ge_composedat__c check (hidden_at >= composed_at),
  constraint dw2_postrevs_hidden__c_null check(hidden_at is null = hidden_by_id is null)
);

create index dw2_postrevs_postid_prevnr__i on dw2_post_revisions(site_id, post_id, previous_nr) where previous_nr is not null;
create index dw2_postrevs_composedby__i on dw2_post_revisions(site_id, composed_by_id);
create index dw2_postrevs_approvedby__i on dw2_post_revisions(site_id, approved_by_id) where approved_by_id is not null;
create index dw2_postrevs_hiddenby__i on dw2_post_revisions(site_id, hidden_by_id) where hidden_by_id is not null;


update dw2_posts set safe_version = 1, current_version = 1, approved_version = 1;

alter table dw2_posts rename safe_version to safe_rev_nr;
alter table dw2_posts rename current_version to current_rev_nr;
alter table dw2_posts rename approved_version to approved_rev_nr;

alter table dw2_posts add previous_rev_nr int;

