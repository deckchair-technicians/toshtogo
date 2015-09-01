(ns toshtogo.test.client.clients.large-error-converting-client-test
  (:require [midje.sweet :refer :all]

            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.clients.large-error-converting-client :refer :all]))

(fact "We can persist large errors and replace them with a url, for example, so that we don't try to store huge
       error bodies in postgres"
  ; TODO: Replace underlying-client with a throw-unsupported implementation, once Bowen supports it
  (let [result-sent-to-underlying-client (atom nil)
        transformer-saw-error            (atom nil)
        original-large-error             {:too-big (range 15000)}
        transformed-error                {:error-url "http://some/proper/file/store"}
        error-transformer                (reify ErrorTransformer
                                           (transform [this error]
                                             (reset! transformer-saw-error error)
                                             transformed-error))
        underlying-client                (reify Client
                                           (put-job! [this job-id job-req])
                                           (get-job [this job-id])
                                           (get-jobs [this query])
                                           (get-tree [this tree-id])
                                           (get-job-types [this])
                                           (pause-job! [this job-id])
                                           (retry-job! [this job-id])

                                           (request-work! [this job-type-or-query])
                                           (heartbeat! [this commitment-id])
                                           (complete-work! [this commitment-id result]
                                             (reset! result-sent-to-underlying-client result)))
        large-error-transforming-client  (large-error-transforming-client underlying-client error-transformer)]

    (complete-work! large-error-transforming-client nil (error original-large-error))

    (fact "Error was passed through the transformer"
      @transformer-saw-error
      => original-large-error)

    (fact "Result is whatever came back from the transformer"
      @result-sent-to-underlying-client
      => (error transformed-error))))

(fact "Small errors are passed through as-is"
  ; TODO: Replace underlying-client with a throw-unsupported implementation, once Bowen supports it
  (let [result-sent-to-underlying-client (atom nil)
        small-error                      {:small "error"}
        error-transformer                (reify ErrorTransformer
                                           (transform [this error]
                                             (throw (UnsupportedOperationException. "Error should not reach transformer"))))
        underlying-client                (reify Client
                                           (put-job! [this job-id job-req])
                                           (get-job [this job-id])
                                           (get-jobs [this query])
                                           (get-tree [this tree-id])
                                           (get-job-types [this])
                                           (pause-job! [this job-id])
                                           (retry-job! [this job-id])

                                           (request-work! [this job-type-or-query])
                                           (heartbeat! [this commitment-id])
                                           (complete-work! [this commitment-id result]
                                             (reset! result-sent-to-underlying-client result)))
        large-error-transforming-client  (large-error-transforming-client underlying-client error-transformer)]

    (complete-work! large-error-transforming-client nil (error small-error))

    @result-sent-to-underlying-client
    => (error small-error)))
