create index jobs_request_body_idx on jobs(request_body);

alter table jobs add constraint jobs_unique_request_fungibility_group unique (request_body, fungibility_group_id);

alter table jobs drop column request_hash;