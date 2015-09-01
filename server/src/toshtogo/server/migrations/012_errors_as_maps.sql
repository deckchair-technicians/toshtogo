create type temp_error_type as (stacktrace text);
---
alter table commitment_outcomes
alter column error type json
using
   case
      when error is null then null
      else row_to_json(cast(row(error) as temp_error_type))
   end;
---
drop type temp_error_type;
