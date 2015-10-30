(ns ^:figwheel-always toshtogo.components.panel
  (:require [om.dom :as dom]
            [om.core :as om]))

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
                        heading)
               (dom/div #js {:className (str "panel-body " (when collapsed? "collapse"))}
                        content)))))