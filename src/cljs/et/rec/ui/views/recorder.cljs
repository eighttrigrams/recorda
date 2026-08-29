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

(defn- meter-pos
  "dBFS to a 0..100 position. The scale runs -60 to 0 because everything worth
   seeing on a voice track lives there, and a linear amplitude bar spends most
   of its width on the top 6 dB where nothing interesting happens."
  [db]
  (max 0 (min 100 (* 100 (/ (+ db 60) 60)))))

(defn level-meter
  "Peak with a slow-decaying hold mark.

   The hold is the useful number: speech peaks are brief and a bar without one
   is unreadable at a glance. Aim the hold at about -12 dBFS. Below -30 the
   take will be all preamp noise when you turn it up; above -3 you are close to
   clipping, which cannot be undone."
  []
  (let [{:keys [peak hold recording? monitoring?]} @mic/state
        live? (or recording? monitoring?)
        pdb   (mic/dbfs peak)
        hdb   (mic/dbfs hold)
        ;; Aim peaks at about -12 dBFS. Below -20 the take is mostly preamp
        ;; noise once it is turned up to a normal level, which is the state
        ;; every recording made here has been in so far; above -3 there is no
        ;; headroom left for a loud syllable and clipping cannot be undone.
        verdict (cond (not live?) nil
                      (< hdb -20) ["low" "low — turn the interface gain up"]
                      (> hdb -3)  ["hot" "hot — back the gain off"]
                      :else       ["good" "good — aim peaks near -12"])]
    [:span.meter-wrap
     [:span.meter {:class (when live? "live")}
      [:span.meter-fill {:style {:width (str (meter-pos pdb) "%")}}]
      (when (and live? (> hold 0))
        [:span.meter-hold {:style {:left (str (meter-pos hdb) "%")}}])]
     (when live?
       [:span.meter-read {:class (first verdict)}
        (if (> hold 0) (str (js/Math.round hdb) " dB") "—")
        " " (second verdict)])]))

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
     [level-meter]
     (when-not (state/busy?)
       [:button.danger
        {:on-click #(if (:monitoring? @mic/state)
                      (mic/stop-monitor!)
                      (mic/monitor! (:name chosen-mic)))
         :title "Open the mic and watch the level, so the gain can be set before recording"}
        (if (:monitoring? @mic/state) "stop check" "check level")])
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
