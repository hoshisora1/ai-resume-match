create table analysis_submission_idempotency (
    id bigint not null auto_increment,
    idempotency_key_hash char(64) not null,
    request_fingerprint char(64) not null,
    task_id bigint,
    created_at timestamp(6) not null,
    primary key (id),
    constraint uk_analysis_submission_idempotency_key unique (idempotency_key_hash),
    constraint uk_analysis_submission_idempotency_task unique (task_id),
    constraint fk_analysis_submission_idempotency_task
        foreign key (task_id) references analysis_task (id)
);
