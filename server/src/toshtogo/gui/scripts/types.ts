/**
 * @author Drew Noakes https://drewnoakes.com
 */

export interface Error
{
    message: string;
    stacktrace: string;
}

export interface Job
{
    job_id: string;
    outcome: string;
    job_type: string;
    request_body: any;
    result_body: any;
    error: Error;
    job_created: string;
    contract_claimed: string;
    contract_finished: string;
    notes: string;
}
