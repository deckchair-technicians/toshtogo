-- AGENTS
create table agents (
  agent_id         uuid           primary key,
  hostname         varchar(64)    not null,
  system_name      varchar(64)    not null,

  unique (hostname, system_name)
);

create index agent_idx on agents (hostname, system_name);

-- JOBS
create table jobs (
  job_id           uuid           primary key,
  requesting_agent uuid           not null references agents(agent_id),
  created          timestamp      not null
);

create index job_created_idx on jobs (created);

create table job_tags (
  job_id           uuid           not null references jobs(job_id),
  tag              varchar(128)   not null
);

create index job_tag_idx on job_tags using hash(tag);

create table job_dependencies (
  dependency_id      uuid         primary key,
  parent_job_id      uuid         references jobs(job_id),
  child_job_id       uuid         references jobs(job_id),
  request_merge_path varchar(256) not null,

  unique (parent_job_id, child_job_id)
);

-- CONTRACTS
create table contracts (
  contract_id      uuid           primary key,
  job_id           uuid           not null references jobs(job_id),
  agent            uuid           references agents(agent_id),
  created          timestamp      not null,
  claimed          timestamp,
  finished         timestamp,
  outcome          varchar(16) -- success, error, replaced, timeout, try-later
);

create index contract_created_idx on contracts (created);
