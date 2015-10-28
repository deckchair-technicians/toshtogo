(ns ^:figwheel-always toshtogo.components.pager
  (:require [om.dom :as dom]
            [goog.string :as gstring]
            [om.core :as om]
            [toshtogo.util.history :as history]))

(defn pager [{:keys [paging query]} _]
  (reify om/IRender
    (render [this]
      (let [{:keys [page pages]} paging
            first-page? (= 1 page)
            last-page? (= page (count pages))]
        (when (> (count pages) 1)
          (dom/nav nil
                   (apply dom/ul #js {:className "pagination"}
                          (dom/li nil
                                  ((if first-page?
                                     dom/span
                                     dom/a) #js {:ariaLabel "Previous"
                                                 :href (history/build-url (update-in query [:page] dec))}
                                   (dom/span #js {:ariaHidden true}
                                             (gstring/unescapeEntities "&laquo;"))))
                          (concat
                            (map-indexed (fn [i u]
                                           (dom/li #js {:className (if (= page (inc i)) "active" "")}
                                                   (dom/a #js {:href (history/build-url (assoc query :page (inc i)))}
                                                          (inc i))))
                                         pages)
                            [(dom/li nil
                                     ((if last-page?
                                        dom/span
                                        dom/a) #js {:ariaLabel "Next"
                                                    :href (history/build-url (update-in query [:page] inc))}
                                      (dom/span #js {:ariaHidden true}
                                                (gstring/unescapeEntities "&raquo;"))))]))))))))
