(ns ^:figwheel-always toshtogo.components.pager
  (:require [om.dom :as dom]
            [goog.string :as gstring]
            [om.core :as om]))

(defn pager [{:keys [page pages]} _ {:keys [navigate]}]
  (reify om/IRender
    (render [this]
      (when (> (count pages) 1)
        (dom/nav nil
          (apply dom/ul #js {:className "pagination"}
                 (dom/li nil
                   (dom/a #js {:ariaLabel "Previous"
                               :onClick   (fn [_]
                                            (navigate (nth pages (dec (dec page)))))}
                     (dom/span #js {:ariaHidden true}
                       (gstring/unescapeEntities "&laquo;"))))
                 (concat
                   (map-indexed (fn [i u]
                                  (dom/li #js {:onClick   (fn [_]
                                                            (navigate u))
                                               :className (if (= page (inc i)) "active" "")}
                                    (dom/a nil
                                      (inc i))))
                                pages)
                   [(dom/li nil
                      (dom/a #js {:ariaLabel "Next"
                                  :onClick   (fn [_]
                                               (navigate (nth pages page)))}
                        (dom/span #js {:ariaHidden true}
                          (gstring/unescapeEntities "&raquo;"))))])))))))
