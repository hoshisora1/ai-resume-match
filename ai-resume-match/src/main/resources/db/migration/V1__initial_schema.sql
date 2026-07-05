create table resume (
    id bigint not null auto_increment,
    file_name varchar(255) not null,
    raw_text longtext not null,
    structured_summary longtext not null,
    created_at timestamp(6) not null,
    primary key (id)
);

create table job_description (
    id bigint not null auto_increment,
    content longtext not null,
    skill_tags varchar(255) not null,
    created_at timestamp(6) not null,
    primary key (id)
);

create table analysis_task (
    id bigint not null auto_increment,
    resume_id bigint not null,
    job_description_id bigint not null,
    status varchar(32) not null,
    attempt_count integer not null default 0,
    max_attempts integer not null default 3,
    failure_code varchar(64),
    failure_message varchar(255),
    next_retry_at timestamp(6),
    started_at timestamp(6),
    completed_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    constraint fk_analysis_task_resume foreign key (resume_id) references resume (id),
    constraint fk_analysis_task_job_description foreign key (job_description_id) references job_description (id)
);

create index idx_analysis_task_resume on analysis_task (resume_id);
create index idx_analysis_task_job_description on analysis_task (job_description_id);
create index idx_analysis_task_status_updated on analysis_task (status, updated_at);
create index idx_analysis_task_status_next_retry on analysis_task (status, next_retry_at);

create table match_report (
    id bigint not null auto_increment,
    task_id bigint not null,
    match_score integer not null,
    report_content longtext not null,
    created_at timestamp(6) not null,
    primary key (id),
    constraint uk_match_report_task unique (task_id),
    constraint fk_match_report_task foreign key (task_id) references analysis_task (id)
);

create table analysis_outbox (
    id bigint not null auto_increment,
    event_type varchar(64) not null,
    aggregate_type varchar(64) not null,
    aggregate_id bigint not null,
    payload_json longtext not null,
    status varchar(32) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp(6),
    last_error varchar(1024),
    created_at timestamp(6) not null,
    published_at timestamp(6),
    primary key (id)
);

create index idx_analysis_outbox_due on analysis_outbox (status, next_attempt_at, id);
create index idx_analysis_outbox_aggregate on analysis_outbox (aggregate_type, aggregate_id);
