/// <reference path="../chosen/chosen.jquery.d.ts" />
/// <reference path="../lib/jquery.d.ts" />
/// <reference path="../lib/jquery.url.d.ts" />
/// <reference path="../lib/lodash.d.ts" />
/// <reference path="../lib/moment.d.ts" />

import DOMTemplate = require('./DOMTemplate');
import types = require('./types');

interface JobsResponse
{
    data: types.Job[];
    paging: {
        page: number;
        pages: string[];
    }
}

function submitForm(pageNum: number): void
{
    var form = $('#jobs-form'),
        baseUrl = form.attr('action'),
        serialized = form.serialize(),
        queryString = (serialized ? serialized + "&" : "") + "page=" + pageNum,
        url = baseUrl + '?' + queryString;

    $.getJSON(url, response => onJobsData(response));
}

var tableTemplate = new DOMTemplate("jobs-table-template");
var pagerTemplate = new DOMTemplate("pager-template"),
    tableContainer = $('#jobs-table-container'),
    pagerContainer = $('#pagination-container');

function onJobsData(jobs: JobsResponse)
{
    var classByOutcome = {
//      waiting: "info",
        error: "danger",
        running: "info",
        cancelled: "warning"
    };

    // Populate the table

    var tableData = _.map(jobs.data, job =>
        ({
            linkText: job.job_type,
            linkUrl: "/jobs/" + job.job_id,
            notes: job.notes,
            createdDate: moment(job.job_created).format("ddd Do MMM"),
            createdTime: moment(job.job_created).format("HH:mm:ss"),
            startedTime: moment(job.contract_claimed).format("HH:mm:ss"),
            finishedTime: moment(job.contract_finished).format("HH:mm:ss"),
            status: job.outcome,
            rowClass: classByOutcome[job.outcome] || job.outcome
        }));

    tableContainer.empty().append(tableTemplate.create(tableData));

    // Populate the pager

    var currentPage = jobs.paging.page,
        pagerData = _.map(jobs.paging.pages, (pageUrl, index) =>
        ({
            num: index + 1,
            className: (currentPage === index + 1) ? 'active' : ''
        }));
    pagerContainer.empty().append(pagerTemplate.create(pagerData));

    pagerContainer.find('ul.pagination a').click(e =>
    {
        var a = <HTMLAnchorElement>e.target;
        submitForm(parseInt(a.dataset['index']));
        return false;
    });
}

/** Request all job types from the server, and asynchronously populate the 'job types' selector. */
function loadJobTypes(): void
{
    var jobTypeUrl = "/api/metadata/job_types";
    var jobTypesSelect = $('#job-type-select');

    jobTypesSelect.empty();

    $.getJSON(jobTypeUrl,
        (jobTypes: string[]) =>
        {
            _.each(jobTypes, jobType => {
                var option = document.createElement('option');
                option.value = jobType;
                option.text = jobType;
                jobTypesSelect.append(option);
            });
            // Trigger a refresh
            jobTypesSelect.trigger("chosen:updated");
        });
}

// Initial page population

submitForm(parseInt(purl().param('page')) || 1);

loadJobTypes();

$(".chosen-select").chosen().change(() => submitForm(1));
