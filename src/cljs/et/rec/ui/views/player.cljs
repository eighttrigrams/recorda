(ns et.rec.ui.views.player
  "The stage: one picture, two lanes, one playhead.

   The audio is not an element here — it is an AudioBuffer played on the audio
   clock, in `et.rec.ui.engine`. What is left for this namespace is the picture,
   which is **slaved to that clock**, and the lanes, which are drawn from it.

   Correcting the video is cheap in a way correcting the audio is not: its rate
   can be bent by several percent with nothing visible, and it can be seeked
   outright at a cost of one frame. So every correction happens here, where it
   does no harm, and the sound plays exactly as recorded."
  (:require [et.rec.ui.engine :as engine]
            [et.rec.ui.state :as state]
            [et.rec.ui.waveform :refer [waveform]]
            [reagent.core :as r]))

(defonce ^:private video-el (atom nil))
(defonce ^:private playhead-el (atom nil))

(def ^:private video-deadband
  "Video drift below this is left alone. The eye does not find a screencast's
   pointer forty milliseconds early."
  0.04)

(def ^:private video-hard-seek
  "Past this the rate trim would take too long to close the gap, so the picture
   jumps. Costs a frame, which is invisible; the equivalent on the audio side
   is a click, which is not — that asymmetry is why this number can be small
   here and could not be there."
  0.25)

(def ^:private max-rate-trim 0.08)

(defn- tick! []
  (let [v   @video-el
        pos (engine/position)
        dur (engine/duration)]
    (when-let [ph @playhead-el]
      (when (pos? dur)
        (set! (.. ph -style -left)
              (str "calc(58px + (100% - 58px) * " (/ pos dur) ")"))))
    (when v
      ;; `running?`, not `playing?` — the picture must not move until the audio
      ;; clock does. See the engine's docstring for what happens when it does.
      (if (engine/running?)
        (do
          (when (.-paused v) (.play v))
          (let [drift (- (.-currentTime v) pos)
                mag   (js/Math.abs drift)]
            (cond
              (> mag video-hard-seek) (set! (.-currentTime v) pos)
              (< mag video-deadband)  (when (not= 1 (.-playbackRate v))
                                        (set! (.-playbackRate v) 1))
              :else (set! (.-playbackRate v)
                          (+ 1 (max (- max-rate-trim)
                                    (min max-rate-trim (* -0.7 drift))))))))
        (do (when-not (.-paused v) (.pause v))
            (when (not= 1 (.-playbackRate v)) (set! (.-playbackRate v) 1))
            ;; keep the frame under the playhead while parked
            (when (> (js/Math.abs (- (.-currentTime v) pos)) 0.05)
              (set! (.-currentTime v) pos))))))
  (js/requestAnimationFrame tick!))

(defonce ^:private _ticker (js/requestAnimationFrame tick!))

;; The timecode only has to be legible, not smooth, and each of these
;; re-renders the stage. The playhead is not routed through here — it is
;; written straight to the DOM above, because sixty re-renders a second is
;; main-thread work and main-thread work is what glitches audio.
(defonce ^:private _clock
  (js/setInterval #(when (engine/playing?)
                     (swap! state/app assoc :time (engine/position)))
                  100))

;; --- transport -------------------------------------------------------------

(defn- fmt [t]
  (let [t (max 0 (or t 0))
        m (js/Math.floor (/ t 60))
        s (js/Math.floor (mod t 60))
        d (js/Math.floor (* 10 (mod t 1)))]
    (str m ":" (when (< s 10) "0") s "." d)))

(defn toggle! []
  (engine/toggle!)
  (swap! state/app assoc :time (engine/position)))

(defn nudge! [dt]
  (engine/nudge! dt)
  (swap! state/app assoc :time (engine/position)))

(defn- seek-from-event [e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        frac (/ (- (.-clientX e) (.-left rect)) (.-width rect))]
    (engine/seek! (* (max 0 (min 1 frac)) (engine/duration)))
    (swap! state/app assoc :time (engine/position))))

;; --- the lanes -------------------------------------------------------------

(defn- lanes []
  (let [peaks (:peaks @state/app)]
    [:div.lanes
     [:div.lane.lane-video
      [:div.lane-label "Video"]
      [:div.lane-body {:on-click seek-from-event}
       [:div.video-strip]]]
     [:div.lane.lane-audio
      [:div.lane-label "Audio"]
      [:div.lane-body {:on-click seek-from-event}
       (if peaks
         [waveform (:peaks peaks)]
         [:div.empty {:style {:padding "8px"}} "reading waveform…"])]]
     [:div.playhead {:ref #(reset! playhead-el %) :style {:left "58px"}}]]))

(defn player []
  (let [take (state/selected-take)]
    (if-not take
      [:div.panel [:div.empty "No take selected. Record one, or pick one on the left."]]
      (let [id      (:id take)
            estate  @engine/state
            ready?  (:ready? estate)]
        [:div.panel
         [:div.video-wrap
          ;; Keyed on the id so choosing another take remounts the element
          ;; rather than swapping src underneath a running decoder. Muted, and
          ;; not merely turned down: its own audio track does not exist, and
          ;; muted is also what lets it autoplay when the clock says go.
          ^{:key id}
          [:video {:ref     #(reset! video-el %)
                   :src     (str "/media/" id "/video.mp4")
                   :muted   true
                   :preload "auto"
                   :on-click (fn [_] (toggle!))}]]
         [:div.transport
          [:button {:on-click (fn [_] (toggle!)) :disabled (not ready?)}
           (cond (:loading? estate) "Decoding…"
                 (:playing? estate) "Pause"
                 :else              "Play")]
          [:button {:on-click (fn [_] (engine/seek! 0)) :disabled (not ready?)} "Start"]
          ;; The one place the two tracks become one file again. Everything
          ;; else in recorda keeps them apart on purpose.
          [:button {:on-click (fn [_] (state/export! id))
                    :disabled (:exporting? @state/app)}
           (if (:exporting? @state/app) "Exporting…" "Export mp4")]
          [:span.timecode
           [:b (fmt (:time @state/app))] " / " (fmt (or (:duration take) 0))]]
         [lanes]
         [:div.meta-line
          [:span (:width take) "×" (:height take)]
          [:span "audio: " (or (:audio-source take) "ffmpeg")]
          (when-let [db (:peak-dbfs take)]
            [:span {:style (when (< db -30) {:color "var(--record)"})}
             "peak " (js/Math.round db) " dBFS"
             (when (< db -30) " — recorded too quietly")])
          [:span "mic led the picture by "
           (js/Math.round (* 1000 (or (:audio-lead take) (:audio-offset take) 0))) " ms"]
          [:span [:a {:href (str "/media/" id "/audio.wav") :download true} "audio.wav"]]
          [:span [:a {:href (str "/media/" id "/video.mp4") :download true} "video.mp4"]]]]))))
