$(document).ready(function () {
        $.getJSON("/api/jobs/" + purl().segment(-1), function (job) {
            $('#all-the-jsons').JSONView(job);

            $(".job-type").text(job.job_type);
            $("#request").JSONView(job.request_body);
            $("#response").JSONView(job.result_body);
        })
    }
);