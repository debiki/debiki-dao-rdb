
alter table dw1_pages drop column deleted_by_id;

alter table dw2_audit_log alter column did_what type smallint using (
    case did_what
        when 'CrSt' then 1
        when 'TsCr' then 2
        when 'NwPg' then 3
        when 'NwPs' then 4
        when 'NwCt' then 5
        when 'EdPs' then 6
        when 'ChPT' then 7
        when 'UpFl' then 8
        else null -- this would generate an error
    end);

alter table dw2_audit_log add constraint dw2_auditlog_didwhat__c_in check (
  did_what between 1 and 200);
