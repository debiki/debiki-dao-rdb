
alter table review_tasks3 add column decided_at timestamp;

alter table review_tasks3 add constraint reviewtasks_c_decidedat_ge_createdat check (
  (decided_at >= created_at));

alter table review_tasks3 drop constraint reviewtasks_completedat_atrevnr__c_nn;
alter table review_tasks3 add constraint reviewtasks_c_decided_compl_match_atrevnr check (
  (decided_at is not null or completed_at is not null) or (completed_at_rev_nr is null));

alter table review_tasks3 drop constraint reviewtasks_completedat_by__c_nn;
alter table review_tasks3 add constraint reviewtasks_c_decided_compl_match_by check (
  (decided_at is null and completed_at is null) = (completed_by_id is null));


alter table review_tasks3 rename column resolution to decision;
update review_tasks3 set decision = 1001 where decision = 1;

create index reviewtasks_decicions_to_do_i on review_tasks3 (decided_at)
  where decided_at is not null and completed_at is null and invalidated_at is null;

