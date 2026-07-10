create index idx_analysis_task_created_id on analysis_task (created_at, id);
create index idx_analysis_task_status_created_id on analysis_task (status, created_at, id);
