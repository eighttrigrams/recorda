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
            [et.rec.ui.views.recorder :as recorder]
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

(defn- trimmed? [take]
  (boolean (some #(or (:out %) (:dropped %)) (:segments take))))

(defn player
  "The project pane: what this video is, and everything you do to it.

   Recording lives here rather than in the header because a project *is* one
   video — the first sitting opens it and every later one is appended, so there
   is no such thing as recording without somewhere for it to go."
  []
  (let [take (state/selected-take)]
    (if-not take
      [:div.panel [:div.empty "Pick a project on the left, or make a new one."]]
      (let [id    (:id take)
            ver   (str (:duration take))
            estate @engine/state
            ready? (:ready? estate)
            empty? (or (= "empty" (:status take)) (nil? (:duration take)))]
        [:div.panel
         [:div.project-head
          [:h2 {:on-click (fn [_] (when-let [t (js/prompt "Title" (:title take))]
                                    (state/rename! id t)))
                :title "Click to rename"}
           (:title take)]
          [:div.spacer]
          [recorder/record-button id]]

         (if empty?
           [:div.empty "Nothing recorded yet. Press Record, and it becomes the opening of this video."]
           [:div
            [:div.video-wrap
             ;; Keyed on the assembly's version as well as the id: appending or
             ;; trimming rewrites the file under the same name, and an element
             ;; keyed on the id alone would go on playing what it already had.
             ^{:key (str id "-" ver)}
             [:video {:ref     #(reset! video-el %)
                      :src     (str "/media/" id "/video.mp4?v=" ver)
                      :muted   true
                      :preload "auto"
                      :on-click (fn [_] (toggle!))}]]
            [:div.transport
             [:button {:on-click (fn [_] (toggle!)) :disabled (not ready?)}
              (cond (:loading? estate) "Decoding…"
                    (:playing? estate) "Pause"
                    :else              "Play")]
             [:button {:on-click (fn [_] (engine/seek! 0)) :disabled (not ready?)} "Start"]
             [:span.timecode
              [:b (fmt (:time @state/app))] " / " (fmt (or (:duration take) 0))]
             [:div.spacer]
             ;; Trim writes a number and drops nothing, so this is safe to
             ;; press and safe to undo — which is what makes "record, back up a
             ;; bit, carry on" a workflow rather than a gamble.
             [:button {:on-click (fn [_] (state/trim! id (:time @state/app)))
                       :disabled (or (state/busy?) (< (:time @state/app) 0.1))
                       :title "Cut everything after the playhead; the next recording carries on from here"}
              "Trim to playhead"]
             (when (trimmed? take)
               [:button.danger {:on-click (fn [_] (state/untrim! id))
                                :disabled (state/busy?)
                                :title "Put every trim and every dropped sitting back"}
                "undo trim"])
             [:button {:on-click (fn [_] (state/export! id))
                       :disabled (:exporting? @state/app)}
              (if (:exporting? @state/app) "Exporting…" "Export mp4")]]
            [lanes]
            [:div.meta-line
             [:span (:width take) "×" (:height take)]
             (let [live (count (remove #(or (:dropped %) (:pending %)) (:segments take)))]
               [:span (if (= 1 live) "one sitting" (str live " sittings"))])
             (when-let [db (:peak-dbfs take)]
               [:span {:style (when (< db -30) {:color "var(--record)"})}
                "peak " (js/Math.round db) " dBFS"
                (when (< db -30) " — recorded too quietly")])
             [:span [:a {:href (str "/media/" id "/audio.wav?v=" ver) :download true} "audio.wav"]]
             [:span [:a {:href (str "/media/" id "/video.mp4?v=" ver) :download true} "video.mp4"]]]])]))))
