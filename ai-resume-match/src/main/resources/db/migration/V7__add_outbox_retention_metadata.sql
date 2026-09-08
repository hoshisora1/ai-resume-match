alter table analysis_outbox
    add column terminal_at timestamp(6) null;

update analysis_outbox
set terminal_at = coalesce(published_at, created_at)
where status in ('PUBLISHED', 'DEAD');

create index idx_analysis_outbox_terminal
    on analysis_outbox (status, terminal_at, id);

create index idx_analysis_idempotency_created
    on analysis_submission_idempotency (created_at, id);
