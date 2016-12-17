
alter table pages3 add column hidden_at timestamp;
alter table pages3 add constraint dw1_pages_createdat_hiddenat__c_le CHECK (created_at <= hidden_at);

