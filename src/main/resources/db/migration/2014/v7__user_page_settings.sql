

create table dw1_role_page_settings (
  site_id varchar not null,
  role_id varchar not null,
  page_id varchar not null,
  notf_level varchar not null,
  constraint dw1_ropgst_site_role_page__p primary key (site_id, role_id, page_id),
  constraint dw1_ropgst_site_page__r__pages foreign key (site_id, page_id) references dw1_pages(tenant, guid), -- index: dw1_ropgst_site_page
  constraint dw1_ropgst_site_role__r__roles foreign key (site_id, role_id) references dw1_users(tenant, sno), -- index: dw1_ropgst_site_role_page__p
  constraint dw1_ropgst_notflevel__c_in check (notf_level in ('W', 'T', 'R', 'M'))
);

create index dw1_ropgst_site_page on dw1_role_page_settings (site_id, page_id);

