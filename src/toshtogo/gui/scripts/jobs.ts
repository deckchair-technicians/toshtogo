/// <reference path="../chosen/chosen.jquery.d.ts" />
/// <reference path="../lib/jquery.d.ts" />
/// <reference path="../lib/jquery.url.d.ts" />
/// <reference path="../lib/moment.d.ts" />

function filterForm() {
  return $('#jobs-form');
}

function submitForm(pageNum) {
  submit(filterForm().attr('action'), serializeForm() + "page=" + pageNum)
}

function submit(url, queryString) {
  $.getJSON(url + '?' + queryString, function (json) {
    handleJobs(json);
  });
}

function mapJobOutcome(outcome) {
  var map = {
    success: "success",
//    waiting: "info",
    error: "danger",
    running: "info",
    cancelled: "warning"
  };

  return map[outcome] || outcome;
}

function loadJobTypes() {
  var jobTypeUrl = "/api/metadata/job_types";
  var jobTypesSelect = $('#job-type-select');

  jobTypesSelect.empty();

  $.getJSON(jobTypeUrl, function (jobTypes) {
    jobTypes.forEach(function (jobType) {
      var jobOption = $('#job-type-template').clone().removeClass('template').removeAttr('id').appendTo(jobTypesSelect);
      jobOption.attr('value', jobType);
      jobOption.text(jobType);
    });
    jobTypesSelect.trigger("chosen:updated");
  })
}

function handleJobs(jobs) {
  var jobs_table_body = $('#jobs-table-body');
  jobs_table_body.empty();

  jobs.data.forEach(function (job) {
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
    el.addClass(mapJobOutcome(job.outcome))
  });

  paginate(jobs);

  jobs_table_body.show();
}

function serializeForm() {
  var serialized = filterForm().serialize();
  return serialized ? serialized + "&" : "";
}

function paginate(jobs) {
  var pagination_control = $('#page-control');
  pagination_control.empty();

  var currentPage = jobs.paging.page;

  jobs.paging.pages.forEach(function (page, index) {
    var pageNumber = index + 1;
    var pager = $('#paginate').clone().removeClass('template').removeAttr('id').appendTo(pagination_control);
    var anchor = pager.find(":first-child");

    if (currentPage == pageNumber) {
      pager.addClass('active');
    }
    anchor.attr('href', '#');
    anchor.text(pageNumber);
    anchor.click(function () {
      submitForm(pageNumber);
      return false;
    })
  });
}

submitForm((purl().param('page') || 1));
loadJobTypes();
$(".chosen-select").chosen();
$(".chosen-select").chosen().change(function () {
  submitForm(1)
});
