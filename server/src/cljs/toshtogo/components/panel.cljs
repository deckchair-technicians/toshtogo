(ns ^:figwheel-always toshtogo.components.panel
  (:require [om.dom :as dom]
            [om.core :as om]
            [goog.string :as gstring]))

(defn panel [{:keys [heading content]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:collapsed? false})
    om/IRenderState
    (render-state [_ {:keys [collapsed?]}]
      (dom/div #js {:className "panel panel-default"}
        (dom/div #js {:className "panel-heading"
                      :onClick   (fn []
                                   (om/update-state! owner [:collapsed?] (partial not)))}
          (dom/i #js {:className "fa fa-expand"
                      :style     #js {:text-align "right"}})
          (gstring/unescapeEntities "&nbsp;&nbsp;")
          heading)
        (dom/div #js {:className (str "panel-body " (when collapsed? "collapse"))}
          content)))))