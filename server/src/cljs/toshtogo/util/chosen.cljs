(ns ^:figwheel-always toshtogo.util.chosen
  (:require [cljs.core.async :refer [chan <! put!]]
            [chosen.core :refer [ichooseu!]]))

(defn init [select <selected>]
  (-> (ichooseu! select)
      (add-watch :change
                 (fn [new-selection]
                   (put! <selected> new-selection)))))
