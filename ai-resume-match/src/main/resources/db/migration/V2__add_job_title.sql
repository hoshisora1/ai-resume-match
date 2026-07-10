alter table job_description add column title varchar(120);

update job_description
set title = concat('岗位 ', id)
where title is null or trim(title) = '';

alter table job_description modify column title varchar(120) not null;
