define(
    [],
    function() {
        function post(job, action) {
            return {
                type: "POST",
                url: '/api/jobs/' + job.job_id + "?action=" + action,
                async: false,
                contentType: "application/json; charset=utf-8",
                data: JSON.stringify({"agent":{"system_name":"gui","system_version":"?","hostname":"?"}}),
                success: function () {
                    getJob();
                },
                error: function (jqXHR, textStatus, errorThrown) {
                    alert("Error\n------------------------\nstatus:" +textStatus +"\nbody:\n" + jqXHR.responseText);
                }
            };
        }

        $.getJSON("/api/jobs/" + purl().segment(-1), function (job) {
            $('#all-the-jsons').JSONView(job);

            var retry_button = $('#retry-button');
            retry_button.hide();
            retry_button.unbind("click");

            var pause_button = $('#pause-button');
            pause_button.hide();
            pause_button.unbind("click");

            if(["waiting", "success", "running", "more-work"].indexOf(job.outcome) < 0){
                retry_button.show();
                retry_button.click(function(){
                    $.ajax
                    (post(job, "retry"))
                });
            }
            if(["waiting", "running"].indexOf(job.outcome) >= 0){
                pause_button.show();
                pause_button.click(function(){
                    $.ajax
                    (post(job, "pause"))
                });
            }

            $(".job-type").text(job.job_type);
            $("#request").JSONView(job.request_body);
            var response = $("#response");
            response.empty();
            if(job.result_body){
                response.JSONView(job.result_body);
            }else if(job.error){
                response.append("<textarea>" + job.error+ "</textarea>");
            }
        });
    }
);
