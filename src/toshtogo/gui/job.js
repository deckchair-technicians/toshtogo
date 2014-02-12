$(document).ready(function getJob() {
        $.getJSON("/api/jobs/" + purl().segment(-1), function (job) {
            $('#all-the-jsons').JSONView(job);

            if(true){
                var retry_button = $('#retry-button');
                retry_button.show();
                retry_button.click(function(){
                    $.ajax
                    ({
                        type: "POST",
                        url: '/api/jobs/' + job.job_id + "?action=retry",
                        async: false,
                        success: function () {
                            getJob();
                        },
                        error: function (jqXHR, textStatus, errorThrown) {
                            alert("Error:\n" + jqXHR.responseText);
                        }
                    })
                });
            }
            $(".job-type").text(job.job_type);
            $("#request").JSONView(job.request_body);
            $("#response").JSONView(job.result_body);
        })
    }
);