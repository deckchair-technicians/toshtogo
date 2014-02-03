(ns toshtogo.test.client.util-test
  (:import [toshtogo.client SenderException])
  (:require [midje.sweet :refer :all]
            [toshtogo.client.util :refer :all]))

(fact "throw-500"
      (throw-500 {:status 500}) => (throws SenderException)
      (throw-500 {:status 200}) => {:status 200}
      (throw-500 nil) => nil)
