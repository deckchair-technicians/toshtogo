alter table jobs add column fungibility_group_id uuid;
---
update jobs set fungibility_group_id = fungibility_groups.f_group_id
from fungibility_groups
where jobs.job_id = fungibility_groups.f_group_job_id;
---
update jobs set fungibility_group_id = job_id where fungibility_group_id is null;
---
alter table jobs alter column fungibility_group_id set not null;
---
drop table fungibility_groups