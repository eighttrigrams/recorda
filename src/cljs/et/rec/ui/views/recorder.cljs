(ns et.rec.ui.views.recorder
  "The strip along the top: what will be recorded, and the button that does it."
  (:require [et.rec.ui.mic :as mic]
            [et.rec.ui.state :as state]
            [reagent.core :as r]))

(defonce ^:private now (r/atom (js/Date.now)))
(defonce ^:private _clock (js/setInterval #(reset! now (js/Date.now)) 100))

(defn- fmt-elapsed [secs]
  (let [s (js/Math.floor secs)]
    (str (js/Math.floor (/ s 60)) ":" (when (< (mod s 60) 10) "0") (mod s 60))))

(defn devices-strip []
  (let [{:keys [chosen-screen chosen-mic ready?]} (:devices @state/app)
        m (:device @mic/state)
        e (:error @mic/state)]
    [:div.devices {:class (when-not ready? "not-ready")}
     (if chosen-screen
       [:span.dev "Screen "
        [:strong (if (:error chosen-screen)
                   (:error chosen-screen)
                   (str (:width chosen-screen) "×" (:height chosen-screen)))]]
       [:span.dev "Screen " [:strong "none found"]])
     ;; The microphone is the browser's, not ffmpeg's — see et.rec.ui.mic for
     ;; why. Until permission is given its real label is unknown, so the name
     ;; recorda will look for is shown instead.
     [:span.dev "Mic "
      [:strong (or m (:name chosen-mic) "default")]
      (when-not m [:span {:style {:opacity 0.55}} " (browser)"])]
     (when e [:span.dev {:style {:color "var(--record)"}} e])
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
     (when proc?
       [:span.processing-note
        (if (:uploading? @state/app) "saving audio…" "finishing…")])
     [:button.record-btn {:class    (str (when rec? "recording recording-now"))
                          :disabled (or proc? (and (not rec?) (not ready)))
                          :on-click #(if rec? (state/stop!) (state/start!))}
      [:span.dot]
      (cond rec? "Stop" proc? "Working" :else "Record")]]))
