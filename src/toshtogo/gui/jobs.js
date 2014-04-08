function filterForm() {
  return $('#jobs-form');
}
function submit_form() {
  var jobs_form = filterForm();
  var url = jobs_form.attr('action');
  submit(url, jobs_form.serialize());
}

function submit(url, queryString) {
  $.getJSON(url + '?' + queryString, function (json) {
    handle_jobs(json);
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
function handle_jobs(jobs) {
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

function paginate(jobs) {
  var pagination_control = $('#page-control');
  pagination_control.empty();

  var currentPage = jobs.paging.page;

  jobs.paging.pages.forEach(function (page, index) {
    var pageNumber = index + 1;
    var pager = $('#paginate').clone().removeClass('template').removeAttr('id').appendTo(pagination_control)
    var anchor = pager.find(":first-child");

    if (currentPage == pageNumber) {
      pager.addClass('active');
    }
    anchor.attr('href', '#');
    anchor.text(pageNumber);
    anchor.click(function(event) {
      submit(filterForm().attr('action'), filterForm().serialize() + "&page="+anchor.text());
      return false;
    })
  });
}

$(document).ready(function () {
    $.getJSON("/api/jobs?page=" + (purl().param('page') || 1), handle_jobs);

    $(".chosen-select").chosen();

    var status_select = $('#job-status-select');

    status_select.chosen().change(submit_form);
  }
);
