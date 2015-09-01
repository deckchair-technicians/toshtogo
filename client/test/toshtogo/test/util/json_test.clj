(ns toshtogo.test.util.json-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [date-time]]
            [clj-time.coerce :refer [to-date]]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [parse-datetime]]
            [toshtogo.util.json :refer :all]))

(fact "Formats dates to the millisecond"
      (let [joda-date (date-time 1986 10 14 4 3 27 456)
            java-date (to-date joda-date)]

        (decode (encode {:joda joda-date :java java-date}))
        => {:joda "1986-10-14T04:03:27.456Z"
            :java "1986-10-14T04:03:27.456+0000"}))
