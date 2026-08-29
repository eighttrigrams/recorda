(ns et.rec.ui.views.player
  "The stage: one picture, two lanes, one playhead.

   **The video element is the clock and the audio follows it.** They are two
   separate media elements because they are two separate files, and they are
   two separate files because that is what makes them separately editable —
   but two elements will not stay together on their own.

   How they are kept together is the whole substance of this namespace, and
   the obvious way is wrong. See `tick!`."
  (:require [et.rec.ui.state :as state]
            [et.rec.ui.waveform :refer [waveform]]
            [reagent.core :as r]))

(defonce ^:private video-el (atom nil))
(defonce ^:private audio-el (atom nil))
(defonce ^:private playhead-el (atom nil))

;; --- keeping two elements together ----------------------------------------

(def ^:private deadband
  "Drift below this is left alone. Correcting a slip nobody can hear costs
   more than the slip."
  0.012)

(def ^:private hard-seek-threshold
  "Drift above this is not drift, it is a fault — a tab that was backgrounded,
   or an output device that never started. Only here is a hard seek worth its
   cost, and even then not more often than the cooldown allows."
  0.5)

(def ^:private hard-seek-cooldown-ms
  "The floor under how often the audio may be seeked.

   Without this the first version's failure mode simply returns at a higher
   threshold: a seek stalls the decoder, the stall creates the drift that
   trips the next seek, and the loop sustains itself. A correction that cannot
   repeat faster than this cannot feed itself, whatever else is wrong."
  2000)

(def ^:private max-rate-trim
  "The most the audio's rate is bent to catch up, as a fraction. Three percent
   closes a 50 ms gap in under two seconds, and browsers preserve pitch across
   a change this small, so it is a slightly early or late word rather than a
   detuned one."
  0.03)

(defonce ^:private last-hard-seek (atom 0))

(defn- tick!
  "Runs every animation frame. Two jobs: move the playhead, and keep the audio
   with the picture.

   **The audio is never seeked to correct ordinary drift.** That was the first
   version and it is a trap worth describing, because it looks like the obvious
   fix and it is self-sustaining. Seeking an audio element flushes its decoder;
   the flush makes it stall; the stall puts it further behind than it was;
   being further behind trips the correction again. Measured on a seven second
   take, that loop fired 33 times — five audible stutters a second — and the
   drift trace was a perfect sawtooth climbing to 68 ms and snapping back.

   So the correction is a **rate trim** instead. Nudging playbackRate a couple
   of percent converges just as fast, flushes nothing, and cannot feed itself.
   A hard seek survives only for the case the trim cannot reach, behind a
   threshold and a cooldown.

   The playhead is moved by writing to the DOM node directly rather than
   through the state atom. Putting the time in the atom re-rendered the whole
   stage sixty times a second, and that main-thread work is itself a source of
   the audio glitches this is trying to avoid. The atom still gets the time,
   but slowly, and only so the timecode has something to print."
  []
  (let [v @video-el a @audio-el]
    (when v
      (when-let [ph @playhead-el]
        (let [dur (or (.-duration v) 0)]
          (when (pos? dur)
            (set! (.. ph -style -left)
                  (str "calc(58px + (100% - 58px) * " (/ (.-currentTime v) dur) ")")))))
      (when a
        (if (.-paused v)
          (when (not= 1 (.-playbackRate a)) (set! (.-playbackRate a) 1))
          (let [drift (- (.-currentTime a) (.-currentTime v))
                mag   (js/Math.abs drift)
                now   (js/Date.now)]
            (cond
              (and (> mag hard-seek-threshold)
                   (> (- now @last-hard-seek) hard-seek-cooldown-ms))
              (do (reset! last-hard-seek now)
                  (set! (.-currentTime a) (.-currentTime v))
                  (set! (.-playbackRate a) 1))

              (< mag deadband)
              (when (not= 1 (.-playbackRate a)) (set! (.-playbackRate a) 1))

              :else
              ;; drift is negative when the audio is behind, so this speeds it
              ;; up; the halving keeps the approach gentle rather than
              ;; overshooting into an oscillation of its own.
              (set! (.-playbackRate a)
                    (+ 1 (max (- max-rate-trim)
                              (min max-rate-trim (* -0.5 drift)))))))))))
  (js/requestAnimationFrame tick!))

(defonce ^:private _ticker (js/requestAnimationFrame tick!))

;; The timecode only has to be legible, not smooth, and every one of these
;; re-renders the stage.
(defonce ^:private _clock
  (js/setInterval
    (fn []
      (when-let [v @video-el]
        (when-not (.-paused v)
          (swap! state/app assoc :time (.-currentTime v)))))
    100))

;; --- transport -------------------------------------------------------------

(defn- fmt [t]
  (let [t (max 0 (or t 0))
        m (js/Math.floor (/ t 60))
        s (js/Math.floor (mod t 60))
        d (js/Math.floor (* 10 (mod t 1)))]
    (str m ":" (when (< s 10) "0") s "." d)))

(defn- seek! [t]
  (let [v @video-el a @audio-el
        t (max 0 (min t (:duration @state/app)))]
    (when v (set! (.-currentTime v) t))
    (when a (set! (.-currentTime a) t) (set! (.-playbackRate a) 1))
    (swap! state/app assoc :time t)))

(defn nudge!
  "Jump by a number of seconds, clamped by seek!. Bound to the arrow keys."
  [dt]
  (seek! (+ (:time @state/app) dt)))

(defn- play!
  "Start the audio, and start the picture only once the audio is really moving.

   Calling play() on both in the same breath is the obvious thing and it opens
   every take out of step. An audio element reports `paused false` and resolves
   its play promise long before a sample reaches the device — an external
   interface has a clock of its own to start, and on this machine the gap is
   tens to hundreds of milliseconds. The video has no such wait, so it is
   already running by the time the first sample lands, and everything
   downstream is left correcting an offset that never had to exist.

   So the audio leads: play it, watch its own clock until it actually advances,
   and start the picture at that moment. The wait is bounded, because an
   element that never starts must not take the video down with it — after that
   the picture runs and the rate trim does what it can."
  []
  (let [v @video-el a @audio-el]
    (when v
      (swap! state/app assoc :playing? true)
      (if (nil? a)
        (.play v)
        (let [t0       (.-currentTime a)
              deadline (+ (js/Date.now) 1000)]
          (set! (.-playbackRate a) 1)
          (.play a)
          ((fn wait []
             (cond
               ;; its clock moved — the device is live, start the picture now
               (> (.-currentTime a) t0)  (.play v)
               (> (js/Date.now) deadline) (.play v)
               :else (js/requestAnimationFrame wait))))))))) 

(defn- pause! []
  (let [v @video-el a @audio-el]
    (when v (.pause v))
    (when a (.pause a) (set! (.-playbackRate a) 1))
    (swap! state/app assoc :playing? false)))

(defn toggle! []
  (if (:playing? @state/app) (pause!) (play!)))

(defn- seek-from-event [e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        frac (/ (- (.-clientX e) (.-left rect)) (.-width rect))]
    (seek! (* (max 0 (min 1 frac)) (:duration @state/app)))))

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
     ;; One playhead for both lanes, which is why they are stacked in a single
     ;; positioned container. Its position is written by tick!, not rendered.
     [:div.playhead {:ref #(reset! playhead-el %) :style {:left "58px"}}]]))

(defn player []
  (let [take (state/selected-take)]
    (if-not take
      [:div.panel [:div.empty "No take selected. Record one, or pick one on the left."]]
      (let [id (:id take)]
        [:div.panel
         [:div.video-wrap
          ;; Keyed on the id so choosing another take remounts the element
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
         [:audio {:ref     #(reset! audio-el %)
                  :src     (str "/media/" id "/audio.wav")
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
