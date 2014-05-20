(ns toshtogo.client.agent
  (:import (java.util.concurrent ExecutorService Executors))
  (:require [clojure.stacktrace :refer [print-cause-trace]]
            [toshtogo.client.protocol :refer :all]))

(defn start [^ExecutorService pool count f]
  (doall (map (fn [_] (.submit pool f)) (range count))))

(defn in-busy-loop
      "Repeatedly call (f shutdown-promise) until shutdown-promise is delivered.

      Catch errors from f and pass to error-handler.

      If error handler fails, we'll print the exception and
      throw an exception."
      [f shutdown-promise error-handler]
  (while (not (realized? shutdown-promise))
    (try
      (f shutdown-promise)

      (catch Throwable e
        (try
          (error-handler e)

          (catch Throwable error-handler-exception
            (println "Error handler itself caused an exception")
            (print-cause-trace error-handler-exception)
            (println "Original exception:")
            (print-cause-trace e)
            (println)
            (throw e)))))))

(defprotocol Service
  (stop [this]))

(defn start-service
      "Returns a reified Service protocol, with a single method (stop [])

      The service runs f in a busy loop across as many threads as requested
      until stop is called.

      f is a function that takes a promise which is delivered when
      the service is stopped (on JVM termination if not before).

      f can inspect the promise periodically to exit quickly and gracefully.

      The service will not allow the JVM to shut down until the currently
      executing function calls complete. If f is long-running and does not
      check shutdown-promise regularly this may mean the JVM takes a long
      time to terminate.

      Exceptions from f will be sent to error-handler, which defaults to
      printing the stack trace to stdout.

      Note that if error-handler throws an exception, the thread will die
      forever."
  ([f & {:keys [error-handler thread-count]
         :or   {error-handler print-cause-trace
                thread-count  1}}]

   (let [shutdown-promise (promise)
         executor-service (Executors/newFixedThreadPool thread-count)
         f-busy-loop      (fn [] (in-busy-loop f shutdown-promise error-handler))
         futures          (start executor-service thread-count f-busy-loop)
         stopper          (delay
                             (.shutdown executor-service)
                             (deliver shutdown-promise true)
                             (doseq [fut futures]
                               @fut))
         service          (reify Service
                             (stop [this]
                               @stopper))]

     (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop service))))

     service)))

(defn wrap-assoc [handler key val & keyvals]
  (fn [job]
    (handler (apply assoc job key val keyvals))))

(defn per-thread-singleton
      "Stolen from clojure.contrib.

      Returns a per-thread singleton function.  f is a function of no
      arguments that creates and returns some object.  The singleton
      function will call f only once for each thread, and cache its value
      for subsequent calls from the same thread.  This allows you to
      safely and lazily initialize shared objects on a per-thread basis.

      Warning: due to a bug in JDK 5, it may not be safe to use a
      per-thread-singleton in the initialization function for another
      per-thread-singleton.  See
      http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5025230"
  [f]
  (let [thread-local (proxy [ThreadLocal] [] (initialValue [] (f)))]
    (fn [] (.get thread-local))))

(defn job-consumer
      "Takes:
      - a client-factory function that returns a Toshtogo client (which will be called at least once per job)
      - a job-type (or a full query map as for get-jobs)
      - a handler function that takes a toshtogo job

      Returns a function that takes a shutdown promise.

      The returned function will call do-work! for the given job type, passing any jobs to
      handler. The job will be enriched with the :shutdown-promise passed in to the wrapping
      function.

      Sleeps for the given number of ms if there is no work to do, so that we don't DOS the
      toshtogo server (defaults to 1 second)."
      [client-factory job-type-or-query handler & {:keys [sleep-on-no-work-ms] :or {sleep-on-no-work-ms 1000}}]
      (let [query (if (map? job-type-or-query)
                    job-type-or-query
                    {:job_type job-type-or-query})
             per-thread-client-factory (per-thread-singleton client-factory)]
        (fn [shutdown-promise]
          (let [client (per-thread-client-factory)
                outcome (when-not (empty? (get-jobs client query))
                          @(do-work! client query (-> handler
                                                      (wrap-assoc :shutdown-promise shutdown-promise))))]
            (when-not outcome
              (Thread/sleep sleep-on-no-work-ms))))))
