(ns et.rec.ui.core
  "Mount, layout, and the two keys worth having."
  (:require [et.rec.ui.state :as state]
            [et.rec.ui.views.library :refer [library]]
            [et.rec.ui.views.player :as player :refer [player]]
            [et.rec.ui.views.recorder :as recorder]
            [reagent.dom.client :as rdomc]))

(defn- build-stamp
  "When the JavaScript this page is running was built.

   Here because 'is the tab I am looking at running the code you just wrote'
   is otherwise unanswerable from the outside, and answering it wrongly costs
   a debugging session — a stale tab reproduces a bug that has been fixed, and
   looks exactly like a fix that did not work."
  []
  (let [ms (some-> js/document .-body (.getAttribute "data-build") js/parseInt)]
    (when (and ms (pos? ms))
      (let [d (js/Date. ms)]
        [:span.build-stamp
         "build " (.padStart (str (.getHours d)) 2 "0")
         ":" (.padStart (str (.getMinutes d)) 2 "0")]))))

(defn app []
  [:div
   [:div.topbar
    [:h1 "recorda"]
    [recorder/devices-strip]
    [:div.spacer]
    [build-stamp]
    [recorder/record-button]]
   (when-let [e (:error @state/app)]
     [:div.panel {:style {:margin-bottom "12px" :color "var(--record)"}} e])
   [:div.columns
    [library]
    [:div.stage [player]]]])

(defn- key-handler [e]
  ;; Not while typing into the rename prompt's input, and not when the page has
  ;; no take on the stage to act on.
  (when (and (:selected @state/app)
             (not (#{"INPUT" "TEXTAREA"} (some-> e .-target .-tagName))))
    (case (.-key e)
      " "          (do (.preventDefault e) (player/toggle!))
      "ArrowLeft"  (player/nudge! -5)
      "ArrowRight" (player/nudge! 5)
      nil)))

(defonce root (rdomc/create-root (.getElementById js/document "app")))

(defn init []
  (state/fetch-devices!)
  (state/fetch-recordings!)
  (state/fetch-status!)
  (state/start-polling!)
  (.addEventListener js/document "keydown" key-handler)
  (rdomc/render root [app]))
