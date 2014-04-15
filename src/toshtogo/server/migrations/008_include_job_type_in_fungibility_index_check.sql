alter table jobs drop constraint jobs_unique_request_fungibility_group;
alter table jobs add constraint jobs_unique_request_fungibility_group unique (job_type, fungibility_group_id, request_body);
