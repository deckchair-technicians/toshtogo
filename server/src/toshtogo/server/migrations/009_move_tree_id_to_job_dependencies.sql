alter table job_dependencies add column link_tree_id uuid references job_trees(tree_id);
---
update job_dependencies 
  set link_tree_id=jobs.home_tree_id
from jobs 
where job_dependencies.parent_job_id = jobs.job_id;
---
alter table job_dependencies alter column link_tree_id set not null;
---
drop table job_tree_members;