alter table match_report
    add column report_schema_version varchar(32) not null default 'markdown-v1';

alter table match_report
    add column structured_report_json longtext null;

alter table match_report
    add column provenance_json longtext null;
