/// <reference path="../chosen/chosen.jquery.d.ts" />
/// <reference path="../lib/jquery.d.ts" />
/// <reference path="../lib/jquery.url.d.ts" />
/// <reference path="../lib/moment.d.ts" />

import types = require('./types');

interface JobsResponse
{
    data: types.Job[];
    paging: {
        page: number;
        pages: string[];
    }
}

function filterForm(): JQuery
{
    return $('#jobs-form');
}

function submitForm(pageNum: number): void
{
    submit(filterForm().attr('action'), serializeForm() + "page=" + pageNum)
}

function submit(url: string, queryString: string): void
{
    $.getJSON(
        url + '?' + queryString,
        response => handleJobs(response));
}

function mapJobOutcome(outcome: string): string
{
    var substitutions = {
//      waiting: "info",
        error: "danger",
        running: "info",
        cancelled: "warning"
    };

    return substitutions[outcome] || outcome;
}

function loadJobTypes(): void
{
    var jobTypeUrl = "/api/metadata/job_types";
    var jobTypesSelect = $('#job-type-select');

    jobTypesSelect.empty();

    $.getJSON(jobTypeUrl,
        (jobTypes: string[]) =>
        {
            jobTypes.forEach(jobType =>
            {
                var jobOption = $('#job-type-template').clone().removeClass('template').removeAttr('id').appendTo(jobTypesSelect);
                jobOption.attr('value', jobType);
                jobOption.text(jobType);
            });
            jobTypesSelect.trigger("chosen:updated");
        })
}

function handleJobs(jobs: JobsResponse)
{
    var jobs_table_body = $('#jobs-table-body');
    jobs_table_body.empty();

    jobs.data.forEach(job =>
    {
        var created = moment(job.job_created);
        var started = moment(job.contract_claimed);
        var finished = moment(job.contract_finished);

        var el = $('.job-row-template.template').clone().removeClass('template').removeAttr('id').appendTo(jobs_table_body);
        var link = el.find('.link');
        link.text(job.job_type);
        link.attr("href", "/jobs/" + job.job_id);
        el.find('.notes').text(job.notes);
        el.find('.created-date').text(created.format('ddd Do MMM'));
        el.find('.created-time').text(created.format('HH:mm:ss'));
        el.find('.started').text(started.format('HH:mm:ss'));
        el.find('.finished').text(finished.format('HH:mm:ss'));
        el.find('.status').text(job.outcome);
        el.addClass(mapJobOutcome(job.outcome));
    });

    paginate(jobs);

    jobs_table_body.show();
}

function serializeForm(): string
{
    var serialized = filterForm().serialize();
    return serialized ? serialized + "&" : "";
}

function paginate(jobs: JobsResponse): void
{
    var pagination_control = $('#page-control');
    pagination_control.empty();

    var currentPage = jobs.paging.page;

    jobs.paging.pages.forEach((page: string, index: number) =>
    {
        var pageNumber = index + 1;
        var pager = $('#paginate').clone().removeClass('template').removeAttr('id').appendTo(pagination_control);
        var anchor = pager.find(":first-child");

        if (currentPage === pageNumber)
        {
            pager.addClass('active');
        }
        anchor.attr('href', '#');
        anchor.text(pageNumber);
        anchor.click(() =>
        {
            submitForm(pageNumber);
            return false;
        });
    });
}

submitForm(parseInt(purl().param('page')) || 1);

loadJobTypes();

$(".chosen-select").chosen().change(() => submitForm(1));
