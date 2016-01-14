
create table watchbar_3(
  site_id varchar not null,
  user_id int not null,
  -- later, convert to json, in PostgreSQL 9.5
  content varchar not null,

  constraint watchbar__p primary key (site_id, user_id),
  constraint watchbar__r__users foreign key (site_id, user_id) references dw1_users (site_id, user_id),
  constraint watchbar_content__c_len check (length(content) between 2 and 20000)
);

