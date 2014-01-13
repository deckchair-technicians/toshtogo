-- AGENTS
create table agents (
  agent_id         uuid           primary key,
  hostname         varchar(64)    not null,
  system_name      varchar(64)    not null,
  system_version   varchar(32)    not null,

  unique (hostname, system_name, system_version)
);

create index agent_idx on agents (hostname, system_name, system_version);

-- JOBS
create table jobs (
  job_id           uuid           primary key,
  requesting_agent uuid           not null references agents(agent_id),
  job_created      timestamp      not null,
  request_body     text           not null
);

create index job_created_idx on jobs (job_created);

create table job_tags (
  job_id           uuid           not null references jobs(job_id),
  tag              varchar(128)   not null
);

create index job_tag_idx on job_tags using hash(tag);

create table job_dependencies (
  dependency_id      uuid         primary key,
  parent_job_id      uuid         references jobs(job_id),
  child_job_id       uuid         references jobs(job_id),

  unique (parent_job_id, child_job_id)
);

-- CONTRACTS
create table contracts (
  contract_id      uuid           primary key,
  job_id           uuid           not null references jobs(job_id),
  contract_number  integer        not null,
  contract_created timestamp      not null,

  unique(job_id, contract_number)
);

create index contract_created_idx  on contracts (contract_created);
create index contract_number_idx   on contracts (contract_number);

create table agent_commitments (
  commitment_id       uuid        primary key,
  commitment_contract uuid        unique not null references contracts(contract_id),
  commitment_agent    uuid        not null references agents(agent_id),
  contract_claimed    timestamp   not null
);

create index commitment_claimed_idx  on agent_commitments (contract_claimed);

create table commitment_outcomes (
  outcome_id        uuid          primary key references agent_commitments(commitment_id),
  error             text,
  contract_finished timestamp     not null,
  outcome           varchar(16)   not null -- success, error,  more-work, timeout, try-later
);

create index commitment_finished_idx on commitment_outcomes (contract_finished);
create index commitment_status_idx   on commitment_outcomes (outcome);

create table job_results (
  job_id           uuid           primary key references jobs(job_id),
  result_body      text           not null
);


-- PUT_IDEMPOTENCY
-- Two halves of a 128 bit murmur hash of
-- the request body
--
-- Used to check that a second put request
-- on the same key is genuinely a resubmission
-- of the same data
--
-- Otherwise we want to warn the client that they're
-- doing something weird
create table put_hashes (
  id               uuid           not null,
  operation_type   varchar(32)    not null,
  hash_1           bigint         not null,
  hash_2           bigint         not null,
  primary key (id, operation_type)
);
