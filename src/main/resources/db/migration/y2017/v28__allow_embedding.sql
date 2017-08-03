
alter table settings3 add column allow_embedding_from varchar;
alter table settings3 add constraint settings_allowembeddingfrom__c_len check (
  length(allow_embedding_from) between 5 and 100);
