(ns toshtogo.jobs.graph
  (:require [cljsjs.d3]
            [om.dom :as dom]
            [om.core :as om]
            [cljs.core.async :refer [chan <! put!]]
            [ajax.core :refer [POST GET]]
            [toshtogo.components.panel :as components.panel]
            [toshtogo.jobs.util :as util]))

(defn render-force-graph
  [data dom-node width height]
  (let [data (clj->js data)

        ; Builds the SVG object
        svg (-> js/d3
                (.select dom-node)
                (.append "svg")
                (.attr "width" width)
                (.attr "height" height))

        ; Creates a force layout
        force (-> js/d3
                  (.-layout)
                  (.force)
                  (.size (clj->js [width height]))
                  (.charge -2400)
                  (.linkDistance 40)
                  (.on "tick" (fn []
                                (-> (.selectAll svg ".link")
                                    (.attr "x1" (fn [d] (-> d .-source .-x)))
                                    (.attr "y1" (fn [d] (-> d .-source .-y)))
                                    (.attr "x2" (fn [d] (-> d .-target .-x)))
                                    (.attr "y2" (fn [d] (-> d .-target .-y))))

                                (-> (.selectAll svg ".node")
                                    (.attr "transform" (fn [d] (str "translate(" d.x "," d.y ")")))))))

        drag (-> force
                 .drag
                 (.on "dragstart" (fn [d]
                                    ; Clears selection
                                    (-> (.selectAll svg ".node")
                                        (.classed "fixed" (set! (.-fixed d)) false))
                                    ; Creates new selection
                                    (this-as this
                                             (-> js/d3
                                                 (.select this)
                                                 (.classed "fixed" (set! (.-fixed d) true)))))))]

    (-> force
        (.nodes (.-nodes data))
        (.links (.-links data))
        .start)

    (-> (.selectAll svg ".link")
        (.data (.-links data))
        (.enter)
        (.append "line")
        (.attr "class" "link"))

    ; Creates node groups
    (-> (.selectAll svg ".node")
        (.data (.-nodes data))
        (.enter)
        (.append "g")
        (.attr "class" "node")
        (.call drag))

    ; Adds circles to nodes
    (-> (.selectAll svg ".node")
        (.append "circle")
        (.attr "r" 12)
        (.style "fill" (fn [d]
                         (case (aget d "outcome")
                           "waiting" "#66ccff"
                           "error" "#ff3300"
                           "running"  "#66ccff"
                           "cancelled" "#ffcc00"
                           "success" "#339900"
                           "no-contract" "#ff00ff"))))

    ; Adds job type text to nodes
    (-> (.selectAll svg ".node")
        (.append "text")
        (.attr "dx" 14)
        (.attr "dy" ".35em")
        (.style "font-family" "monospace")
        (.style "fill" "#000")
        ; Makes text actually readable
        (.style "stroke-width" "0")
        (.text (fn [d] (aget d "job_type"))))))

(defn d3-graph-view
  [data owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:ref "graph-dom-node"}))

    om/IDidUpdate
    (did-update [_ _ _]
      ; Deletes existing graph
      (-> js/d3
          (.select "svg")
          (.remove))
      (render-force-graph data
                          (om/get-node owner "graph-dom-node")
                          (.-offsetWidth (om/get-node owner))
                          500))

    om/IDidMount
    (did-mount [_]
      (render-force-graph data
                          (om/get-node owner "graph-dom-node")
                          (.-offsetWidth (om/get-node owner))
                          500))))

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
      (dom/div #js {:className "row"}
        (dom/div #js {:className "col-md-6"}
          (om/build components.panel/panel {:heading "Dependency JSON"
                                            :content (dom/div #js {:id "graph-json"})}))
        (dom/div #js {:className "col-md-6"}
          (when graph
            (om/build components.panel/panel {:heading "Dependency Graph"
                                              :content (om/build d3-graph-view graph)})))))))
