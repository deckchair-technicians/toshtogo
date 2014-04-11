(ns toshtogo.test.functional.find-jobs-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str]]))

(facts "Can specify order when getting jobs"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             job-type (uuid-str)]

         (put-job! client job-id-1 (job-req {} job-type))
         (Thread/sleep 1)
         (put-job! client job-id-2 (job-req {} job-type))

         (fact "Order by ascending job created time works"
               (ids-and-created-dates client {:order-by [:job_created] :job_type job-type})
               => (contains [(contains {:job_id job-id-1})
                             (contains {:job_id job-id-2})]))

         (fact "Order by descending job created time works"
               (ids-and-created-dates client {:order-by [[:job_created :desc]] :job_type job-type})
               => (contains [(contains {:job_id job-id-2})
                             (contains {:job_id job-id-1})]))))

(facts "Can specify paging when getting jobs"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             job-id-3 (uuid)
             job-type (uuid-str)]

         (put-job! client job-id-1 (job-req {} job-type))
         (Thread/sleep 1)
         (put-job! client job-id-2 (job-req {} job-type))
         (Thread/sleep 1)
         (put-job! client job-id-3 (job-req {} job-type))

         (fact "Getting first page"
               (ids-and-created-dates client {:order-by [:job_created] :job_type job-type :page 1 :page-size 2})
               => (contains [(contains {:job_id job-id-1})
                             (contains {:job_id job-id-2})]))

         (fact "Getting second page"
               (ids-and-created-dates client {:order-by [:job_created] :job_type job-type :page 2 :page-size 2})
               => (contains [(contains {:job_id job-id-3})]))))


(facts "Can filter by depends_on_job_id and dependency_of_job_id"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             job-type (uuid-str)]

         (put-job! client job-id-1 (job-req {:job "1"} job-type
                                            :dependencies
                                            [(assoc (job-req {:job "1.1"} job-type) :job_id job-id-2)]))

         (fact "Searching by depends_on_job_id"
               (ids-and-created-dates client {:depends_on_job_id job-id-2 :job_type job-type})
               => (contains [(contains {:job_id job-id-1})])

               (ids-and-created-dates client {:depends_on_job_id job-id-1 :job_type job-type})
               => empty?)

         (fact "Searching by dependency_of_job_id"
               (ids-and-created-dates client {:dependency_of_job_id job-id-1 :job_type job-type})
               => (contains [(contains {:job_id job-id-2})])

               (ids-and-created-dates client {:dependency_of_job_id job-id-2 :job_type job-type})
               => empty?)))

(facts "Can filter by tags"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             tag-1 (keyword (uuid-str))
             tag-2 (keyword (uuid-str))
             job-type (uuid-str)]

         (put-job! client job-id-1 (job-req {} job-type :tags [tag-1]))
         (put-job! client job-id-2 (job-req {} job-type :tags [tag-1 tag-2]))

         (fact "Filter by one tag"
               (get-and-select client {:tags [tag-1] :job_type job-type} :job_id :tags)
               => (contains [(contains {:job_id job-id-1})
                             (contains {:job_id job-id-2})]
                            :in-any-order))

         (fact "Filter by both tags"
               (get-and-select client {:tags [tag-1 tag-2] :job_type job-type} :job_id :tags)
               => (contains [(contains {:job_id job-id-2})]))

         (fact "No job with matching tags"
               (get-and-select client {:tags [(keyword (uuid-str))] :job_type job-type} :job_id :tags)
               => empty?)))

(facts "Can filter by outcome"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             job-id-3 (uuid)
             job-type (uuid-str)]

         (put-job! client job-id-1 (job-req {:job "success"} job-type))
         @(do-work! client job-type return-success) => truthy

         (put-job! client job-id-2 (job-req {:job "running"} job-type))
         (request-work! client job-type) => truthy

         (put-job! client job-id-3 (job-req {:job "waiting"} job-type))


         (fact "Can get waiting jobs"
               (get-and-select client {:outcome :waiting :job_type job-type} :outcome :job_id)
               => (contains [(contains {:job_id job-id-3 :outcome :waiting})]))

         (fact "Can get running jobs"
               (get-and-select client {:outcome :running :job_type job-type} :outcome :job_id)
               => (contains [(contains {:job_id job-id-2 :outcome :running})]))

         (fact "Can get succeeded jobs"
               (get-and-select client {:outcome :success :job_type job-type} :outcome :job_id)
               => (contains [(contains {:job_id job-id-1 :outcome :success})]))))

(facts "Can filter by multiple outcomes"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             job-id-3 (uuid)
             job-type (uuid-str)]

         (put-job! client job-id-1 (job-req {:job "success"} job-type))
         @(do-work! client job-type return-success) => truthy

         (put-job! client job-id-2 (job-req {:job "running"} job-type))
         (request-work! client job-type) => truthy

         (put-job! client job-id-3 (job-req {:job "waiting"} job-type))


         (fact "Can get jobs of different outcomes"
               (get-and-select client {:outcome [:waiting :running] :job_type job-type} :outcome :job_id)
               => (contains [(contains {:job_id job-id-3 :outcome :waiting}
                                       {:job_id job-id-2 :outcome :running})]))))

(facts "Can filter by name"
       (let [job-id-1 (uuid)
             job-id-2 (uuid)
             name-1 "abc"
             name-2 "abcdef"]

         (put-job! client job-id-1 (-> (job-req {} (uuid-str))
                                       (with-name name-1)))

         (put-job! client job-id-2 (-> (job-req {} (uuid-str))
                                       (with-name name-2)))

         (fact "Can get jobs by name"
               (get-and-select client {:name name-1} :job_name :job_id)
               => (contains [(contains {:job_id job-id-1 :job_name name-1})]))

         (fact "Can get jobs by name prefix"
               (get-and-select client {:name_starts_with name-1} :job_name :job_id)
               => (contains [(contains {:job_id job-id-1 :job_name name-1}
                                       {:job_id job-id-2 :job_name name-2})]))))
