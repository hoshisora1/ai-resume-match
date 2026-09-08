alter table analysis_outbox
    add column lease_token varchar(36) null;

alter table analysis_outbox
    add column lease_until timestamp(6) null;

-- Before V6, PROCESSING rows used next_attempt_at as an implicit recovery deadline.
update analysis_outbox
set lease_until = next_attempt_at
where status = 'PROCESSING';

create index idx_analysis_outbox_lease
    on analysis_outbox (status, lease_until, id);
