create table job_trees (
  tree_id            uuid           primary key,
  root_job_id        uuid           constraint job_trees_root_job_id_fkey
                                    not null
                                    references jobs(job_id)
                                    deferrable initially deferred
);
---
create table job_tree_members (
  membership_tree_id uuid           not null references job_trees(tree_id),
  tree_job_id        uuid           not null references jobs(job_id),

  primary key (membership_tree_id, tree_job_id)
);
---
create index job_tree_members_job_id_idx on job_tree_members (tree_job_id);
---
alter table jobs add column home_tree_id uuid references job_trees(tree_id);
---
do $$
declare 
	root_jobs cursor for 
		select job_id
		from jobs
		left join job_dependencies
			on jobs.job_id = job_dependencies.child_job_id
		where job_dependencies.child_job_id is null;
begin
for root_job in root_jobs loop
  insert into job_trees(tree_id, root_job_id) values (root_job.job_id, root_job.job_id);

  with recursive jobs_in_tree(job_id) as (
      select root_job.job_id job_id
      union
      select d.child_job_id from jobs_in_tree as j, job_dependencies d where d.parent_job_id = j.job_id)

  insert into job_tree_members (select root_job.job_id tree_id, job_id tree_job_id from jobs_in_tree);

  -- apparently we have to define this once every time we want to use it :(
  with recursive jobs_in_tree(job_id) as (
      select root_job.job_id job_id
      union
      select d.child_job_id from jobs_in_tree as j, job_dependencies d where d.parent_job_id = j.job_id)

  update jobs set home_tree_id = root_job.job_id where jobs.job_id in (select job_id from jobs_in_tree);
end loop;
end$$;
---
alter table jobs alter column home_tree_id set not null;