alter table resume
    add column owner_id char(64) not null default '0000000000000000000000000000000000000000000000000000000000000000';

alter table job_description
    add column owner_id char(64) not null default '0000000000000000000000000000000000000000000000000000000000000000';

alter table analysis_task
    add column owner_id char(64) not null default '0000000000000000000000000000000000000000000000000000000000000000';

alter table analysis_submission_idempotency
    add column owner_id char(64) not null default '0000000000000000000000000000000000000000000000000000000000000000';

create index idx_resume_owner on resume (owner_id, id);
create index idx_job_description_owner on job_description (owner_id, id);
create index idx_analysis_task_owner_created on analysis_task (owner_id, created_at, id);
create index idx_analysis_task_owner_status_created on analysis_task (owner_id, status, created_at, id);
create index idx_analysis_submission_idempotency_owner on analysis_submission_idempotency (owner_id, created_at, id);
