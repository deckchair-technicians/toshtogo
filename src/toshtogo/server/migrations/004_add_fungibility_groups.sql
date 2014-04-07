create table fungibility_groups (
  f_group_id            uuid not null,
  f_group_request_hash  uuid not null,
  f_group_job_id        uuid not null references jobs (job_id)
);
create unique index fungibility_groups_idc on fungibility_groups (f_group_id, f_group_request_hash);
