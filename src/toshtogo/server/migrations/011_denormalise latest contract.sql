alter table jobs add column latest_contract uuid references contracts(contract_id);
---
update jobs
  set latest_contract=contracts.contract_id
from contracts
where contracts.job_id = jobs.job_id
  and contracts.contract_number=(select max(contract_number) from contracts c2 where c2.job_id=jobs.job_id);