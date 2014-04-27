(ns toshtogo.client.agent
  (:import (java.util.concurrent ExecutorService Executors))
  (:require [clojure.stacktrace :refer [print-cause-trace]]
            [toshtogo.client.protocol :refer :all]))

(defn start [^ExecutorService pool count f]
  (map (fn [_] (.submit pool f)) (range count)))

(defn in-busy-loop
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
      "Returns a Service, with a single function (stop [])

      The service runs f in a busy loop across as many threads as requested
      until stop is called.

      f is a function that takes a promise which is delivered when
      the service is stopped (on JVM termination). This allows the wrapped
      function a chance to exit quickly.

      The service will not allow the JVM to shut down until the currently
      executing function calls complete. If f is long-running and does not
      check shutdown-promise regularly this may mean the JVM takes a long
      time to terminate.

      Exceptions from f will be sent to error-handler, which defaults to
      printing the stack trace to stdout"
  ([f & {:keys [error-handler thread-count]
         :or   {error-handler print-cause-trace
                thread-count  1}}]

   (let [shutdown-promise (promise)
         executor-service (Executors/newFixedThreadPool thread-count)
         f-busy-loop (fn [] (in-busy-loop f shutdown-promise error-handler))
         futures (start executor-service thread-count f-busy-loop)
         stop (delay (.shutdown executor-service)
                     (deliver shutdown-promise true)
                     (doseq [fut futures]
                       @fut))
         service (reify Service
                   (stop [this]
                     @stop))]

     (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop service))))

     service)))

(defn wrap-assoc [handler key val & keyvals]
  (fn [job]
    (handler (apply assoc job key val keyvals))))

(defn job-consumer
      [client job-type handler & {:keys [sleep-on-no-work-ms] :or {sleep-on-no-work-ms 1000}}]
  (fn [shutdown-promise]
    (let [outcome @(do-work! client job-type (-> handler
                                                 (wrap-assoc :shutdown-promise shutdown-promise)))]
      (when-not outcome
        (Thread/sleep 1000)))))
