alter table jobs drop constraint jobs_unique_request_fungibility_group;
---
alter table jobs drop column fungibility_group_id;
---
alter table jobs add column fungibility_key varchar(128);
--
alter table jobs add constraint jobs_unique_job_type_fungibility_key unique (job_type, fungibility_key);
