(ns toshtogo.jobs.graph
  (:require [cljsjs.d3]
            [om.dom :as dom]
            [om.core :as om]
            [cljs.core.async :refer [chan <! put!]]
            [ajax.core :refer [POST GET]]
            [toshtogo.jobs.util :as util]))

(defn d3-graph-view
  [data owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:ref "graph-dom-node"}))

    om/IDidMount
    (did-mount [_]
      (let [width (.-offsetWidth (om/get-node owner))

            height 500

            data (clj->js data)

            svg (-> js/d3
                    (.select (om/get-node owner "graph-dom-node"))
                    (.append "svg")
                    (.attr "width" width)
                    (.attr "height" height))

            link (atom (.selectAll svg ".link"))

            node (atom (.selectAll svg ".node"))

            force (-> js/d3
                      (.-layout)
                      (.force)
                      (.size (clj->js [width height]))
                      (.charge -400)
                      (.linkDistance 40)
                      (.on "tick" (fn []
                                    (-> @link
                                        (.attr "x1" (fn [d] (-> d .-source .-x)))
                                        (.attr "y1" (fn [d] (-> d .-source .-y)))
                                        (.attr "x2" (fn [d] (-> d .-target .-x)))
                                        (.attr "y2" (fn [d] (-> d .-target .-y))))
                                    (-> @node
                                        (.attr "cx" (fn [d] (-> d .-x)))
                                        (.attr "cy" (fn [d] (-> d .-y)))))))

            drag (-> force
                     .drag
                     (.on "dragstart" (fn [d]
                                        (this-as this
                                          (-> js/d3
                                              (.select this)
                                              (.classed "fixed" (set! (.-fixed d) true)))))))

            _ (-> force
                  (.nodes (.-nodes data))
                  (.links (.-links data))
                  .start)

            _ (reset! link (-> @link
                               (.data (.-links data))
                               (.enter)
                               (.append "line")
                               (.attr "class" "link")))

            _ (reset! node (-> @node
                               (.data (.-nodes data))
                               (.enter)
                               (.append "circle")
                               (.attr "class" "node")
                               (.attr "r" 12)
                               (.on "dblclick" (fn [d]
                                                 (this-as this
                                                   (-> js/d3
                                                       (.select this)
                                                       (.classed "fixed" (set! (.-fixed d) false))))))
                               (.call drag)))]))))

(defn json-view [selector m]
  (.JSONView (js/$ selector) (clj->js m)))

(defn update-json [graph]
  (json-view "#graph-json" graph))

(defn graph-view [graph]
  (reify
    om/IDidMount
    (did-mount [_this]
      (update-json graph))

    om/IDidUpdate
    (did-update [_this _ _]
      (update-json graph))

    om/IRenderState
    (render-state [_this {:keys [<messages>]}]
      (dom/div #js {:className ""}
        (when graph
          (om/build d3-graph-view graph))
        (dom/div #js {:id "graph-json"})))))