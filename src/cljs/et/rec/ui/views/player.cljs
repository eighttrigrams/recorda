(ns et.rec.ui.views.player
  "The stage: one picture, two lanes, one playhead.

   **The video element is the clock and the audio follows it.** They are two
   separate media elements because they are two separate files, and they are
   two separate files because that is what makes them separately editable —
   but two elements will not stay together on their own. So the video is
   played muted and authoritative, and the audio is nudged back whenever it
   has wandered further than a listener would notice."
  (:require [et.rec.ui.state :as state]
            [et.rec.ui.waveform :refer [waveform]]
            [reagent.core :as r]))

(defonce ^:private video-el (atom nil))
(defonce ^:private audio-el (atom nil))

(def ^:private drift-tolerance
  "Seconds of slip tolerated before the audio is pulled back into line.

   Too tight and the correction itself becomes the artefact — reseeking an
   audio element several times a second is audible as a stutter, which is
   worse than the drift being corrected. Too loose and speech visibly lags the
   pointer it belongs to. 80 ms sits under the threshold where narration and
   screen read as separate events, and well above the jitter of two media
   clocks that are actually keeping step."
  0.08)

(defn- fmt [t]
  (let [t (max 0 (or t 0))
        m (js/Math.floor (/ t 60))
        s (js/Math.floor (mod t 60))
        d (js/Math.floor (* 10 (mod t 1)))]
    (str m ":" (when (< s 10) "0") s "." d)))

(defn- tick! []
  (let [v @video-el a @audio-el]
    (when v
      (swap! state/app assoc :time (.-currentTime v))
      (when (and a (not (.-paused v)))
        (let [drift (- (.-currentTime a) (.-currentTime v))]
          (when (> (js/Math.abs drift) drift-tolerance)
            (set! (.-currentTime a) (.-currentTime v)))))))
  (js/requestAnimationFrame tick!))

(defonce ^:private _ticker (js/requestAnimationFrame tick!))

(defn- seek! [t]
  (let [v @video-el a @audio-el
        t (max 0 (min t (:duration @state/app)))]
    (when v (set! (.-currentTime v) t))
    (when a (set! (.-currentTime a) t))
    (swap! state/app assoc :time t)))

(defn- play! []
  (let [v @video-el a @audio-el]
    (when v
      (when a (set! (.-currentTime a) (.-currentTime v)) (.play a))
      (.play v)
      (swap! state/app assoc :playing? true))))

(defn- pause! []
  (let [v @video-el a @audio-el]
    (when v (.pause v))
    (when a (.pause a))
    (swap! state/app assoc :playing? false)))

(defn nudge!
  "Jump by a number of seconds, clamped by seek!. Bound to the arrow keys."
  [dt]
  (seek! (+ (:time @state/app) dt)))

(defn toggle! []
  (if (:playing? @state/app) (pause!) (play!)))

(defn- seek-from-event [e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        frac (/ (- (.-clientX e) (.-left rect)) (.-width rect))]
    (seek! (* (max 0 (min 1 frac)) (:duration @state/app)))))

(defn- lanes []
  (let [{:keys [time duration peaks]} @state/app
        pct (if (pos? duration) (* 100 (/ time duration)) 0)]
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
     ;; One playhead for both lanes. It is offset by the label gutter so that
     ;; it lines up with the bodies rather than the rows.
     [:div.playhead {:style {:left (str "calc(58px + (100% - 58px) * " (/ pct 100) ")")}}]]))

(defn player []
  (let [take (state/selected-take)]
    (if-not take
      [:div.panel [:div.empty "No take selected. Record one, or pick one on the left."]]
      (let [id (:id take)]
        [:div.panel
         [:div.video-wrap
          ;; Keyed on the id so that choosing another take remounts the element
          ;; rather than swapping src underneath a running decoder.
          ^{:key id}
          [:video {:ref      #(reset! video-el %)
                   :src      (str "/media/" id "/video.mp4")
                   :muted    true
                   :preload  "auto"
                   :on-loaded-metadata
                   #(swap! state/app assoc :duration (.. % -target -duration))
                   :on-ended #(swap! state/app assoc :playing? false)
                   :on-click (fn [_] (toggle!))}]]
         ^{:key (str id "-a")}
         [:audio {:ref #(reset! audio-el %) :src (str "/media/" id "/audio.wav")
                  :preload "auto"}]
         [:div.transport
          [:button {:on-click (fn [_] (toggle!))}
           (if (:playing? @state/app) "Pause" "Play")]
          [:button {:on-click (fn [_] (seek! 0))} "Start"]
          [:span.timecode [:b (fmt (:time @state/app))] " / " (fmt (:duration @state/app))]]
         [lanes]
         [:div.meta-line
          [:span (:width take) "×" (:height take)]
          [:span (:mic take) " ch" (:mic-channel take)]
          [:span "mic came up " (js/Math.round (* 1000 (or (:audio-offset take) 0))) " ms late"]
          [:span [:a {:href (str "/media/" id "/audio.wav") :download true} "audio.wav"]]
          [:span [:a {:href (str "/media/" id "/video.mp4") :download true} "video.mp4"]]]]))))
