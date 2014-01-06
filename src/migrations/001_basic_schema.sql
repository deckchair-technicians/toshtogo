-- AGENTS
create table agents (
  agent_id         uuid           primary key,
  hostname         varchar(64)    not null,
  system_name      varchar(64)    not null,
  system_version   varchar(32)    not null,

  unique (hostname, system_name)
);

create index agent_idx on agents (hostname, system_name);

-- JOBS
create table jobs (
  job_id           uuid           primary key,
  requesting_agent uuid           not null references agents(agent_id),
  created          timestamp      not null,
  body             text           not null
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
-- Arguably this table should be split out into
-- contracts, contract_claims, contract_claim_results
-- so that all tables are append-only.
-- But that seems like a join too far.
create table contracts (
  contract_id      uuid           primary key,
  job_id           uuid           not null references jobs(job_id),
  agent            uuid           references agents(agent_id),
  created          timestamp      not null,
  claimed          timestamp,
  finished         timestamp,
  outcome          varchar(16) -- success, error, replaced, timeout, try-later
);

-- We're likely to want to search and sort on all these fields
create index contract_created_idx  on contracts (created);
create index contract_claimed_idx  on contracts (claimed);
create index contract_finished_idx on contracts (finished);
create index contract_status_idx   on contracts (outcome);

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
  id               uuid           primary key,
  hash_1           bigint         not null,
  hash_2           bigint         not null
);
