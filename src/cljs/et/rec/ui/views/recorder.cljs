(ns et.rec.ui.views.recorder
  "The strip along the top: what will be recorded, and the button that does it."
  (:require [et.rec.ui.state :as state]
            [reagent.core :as r]))

(defonce ^:private now (r/atom (js/Date.now)))
(defonce ^:private _clock (js/setInterval #(reset! now (js/Date.now)) 100))

(defn- fmt-elapsed [secs]
  (let [s (js/Math.floor secs)]
    (str (js/Math.floor (/ s 60)) ":" (when (< (mod s 60) 10) "0") (mod s 60))))

(defn devices-strip []
  (let [{:keys [chosen-screen chosen-mic ready?]} (:devices @state/app)]
    [:div.devices {:class (when-not ready? "not-ready")}
     (if chosen-screen
       [:span.dev "Screen "
        [:strong (if (:error chosen-screen)
                   (:error chosen-screen)
                   (str (:width chosen-screen) "×" (:height chosen-screen)))]]
       [:span.dev "Screen " [:strong "none found"]])
     (if chosen-mic
       [:span.dev "Mic " [:strong (:name chosen-mic)]]
       [:span.dev "Mic " [:strong "not found"]])
     [:button.danger {:on-click #(state/refresh-devices!)
                      :disabled (state/busy?)
                      :title "Re-measure the screens — needed after plugging a display in"}
      "rescan"]]))

(defn record-button []
  (let [rec?  (state/recording?)
        proc? (state/processing?)
        ready (get-in @state/app [:devices :ready?])]
    [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
     (when rec?
       (let [started (get-in @state/app [:status :started-at])]
         [:span.elapsed (fmt-elapsed (/ (- @now started) 1000))]))
     (when proc? [:span.processing-note "splitting into tracks…"])
     [:button.record-btn {:class    (str (when rec? "recording recording-now"))
                          :disabled (or proc? (and (not rec?) (not ready)))
                          :on-click #(if rec? (state/stop!) (state/start!))}
      [:span.dot]
      (cond rec? "Stop" proc? "Working" :else "Record")]]))
