alter table jobs add column request_hash uuid;
---
create index request_hash_idx on jobs (request_hash);
