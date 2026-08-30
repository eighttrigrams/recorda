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

;; Drawing a recording area. Kept local to the view because it is a gesture,
;; not state anything else needs — the result is sent to the server and comes
;; back on the project.
(defonce ^:private crop-mode (r/atom false))
(defonce ^:private crop-drag (r/atom nil))

;; Bumped whenever the video's laid-out size could have changed. The overlay
;; reads the element's real geometry, and nothing else would make it re-render
;; when metadata arrives or the window is dragged — so the box would be drawn
;; from a videoWidth of 0 and never corrected.
(defonce ^:private video-tick (r/atom 0))
(defonce ^:private _resize
  (.addEventListener js/window "resize" #(swap! video-tick inc)))

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

(defn- seams
  "Where one piece of recording meets another, in seconds.

   Bookkeeping, not an edit handle: it is the one thing about a project you
   cannot see from the picture or hear from the sound, because a join by stream
   copy leaves no mark. After an insert there are more of these than there are
   sittings — a segment cut in two shows a seam at the cut as well as at its
   ends — which is exactly the thing worth being able to look at.

   Computed from the arrangement when there is one and from the sittings when
   there is not, so it reads the same before and after the first edit. The last
   boundary is dropped: the end of the video is not a seam."
  [take]
  (let [durs (into {} (map (juxt :n :duration)) (:segments take))
        cs   (if (seq (:clips take))
               (:clips take)
               (->> (:segments take)
                    (remove :dropped) (remove :pending) (sort-by :n)
                    (map (fn [s] {:seg (:n s) :out (or (:out s) (:duration s))}))))]
    (->> cs
         (reduce (fn [{:keys [at acc]} c]
                   (let [d   (or (get durs (:seg c)) 0)
                         end (+ at (- (or (:out c) d) (or (:in c) 0)))]
                     {:at end :acc (conj acc end)}))
                 {:at 0 :acc []})
         :acc
         butlast)))

(defn- lanes [take]
  (let [peaks (:peaks @state/app)
        dur   (or (:duration take) 0)
        ;; The playhead is the answer to "where will the new material go", so
        ;; when the next press is not a plain append it says so rather than
        ;; leaving the mode picker to carry that on its own.
        armed (not= :append (or (:record-mode @state/app) :append))]
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
     ;; Positioned exactly the way the playhead is, so a seam and the playhead
     ;; sitting on the same instant land on the same pixel.
     (when (pos? dur)
       (for [t (seams take)]
         ^{:key (str t)}
         [:div.seam {:style {:left (str "calc(58px + (100% - 58px) * " (/ t dur) ")")}
                     :title (str "join at " (fmt t))}]))
     [:div.playhead {:ref   #(reset! playhead-el %)
                     :class (when armed "armed")
                     :style {:left "58px"}}]]))

(defn- video-geometry
  "Where the picture is actually painted, and how many video pixels one CSS
   pixel of it covers.

   **Not the element's box.** A <video> letterboxes its picture inside its box
   (object-fit: contain), so whenever the element's aspect ratio differs from
   the footage's, the image is smaller than the element and offset within it.
   Measured here: a 2560x1440 recording in an 1144x589 element paints as
   1047x589, inset 48.4px from the left. Mapping a gesture through the
   element's width instead of the picture's was 8.5% out.

   The two errors — too small a scale, and a missing offset — cancel at the
   centre and grow toward the edges, so a box drawn near the middle came back
   very slightly too tight on every side. That is a hard thing to see and an
   easy thing to dismiss.

   Returned in the container's coordinates so the overlay can be laid directly
   on the picture, which also stops you drawing on the letterbox bars."
  []
  (when-let [v @video-el]
    (let [elw (.-clientWidth v)  elh (.-clientHeight v)
          vw  (.-videoWidth v)   vh  (.-videoHeight v)]
      (when (and (pos? elw) (pos? elh) (pos? vw) (pos? vh))
        (let [k    (min (/ elw vw) (/ elh vh))   ; css px per video px
              picw (* vw k)
              pich (* vh k)]
          {:left  (+ (.-offsetLeft v) (/ (- elw picw) 2))
           :top   (+ (.-offsetTop v)  (/ (- elh pich) 2))
           :w     picw
           :h     pich
           :scale (/ 1 k)})))))                  ; video px per css px

(defn- rect-from-drag [{:keys [x0 y0 x1 y1]} scale]
  (let [x (min x0 x1) y (min y0 y1)
        w (js/Math.abs (- x1 x0)) h (js/Math.abs (- y1 y0))]
    {:x (js/Math.round (* x scale)) :y (js/Math.round (* y scale))
     :w (js/Math.round (* w scale)) :h (js/Math.round (* h scale))}))

(defn- crop-overlay
  "The area box, drawn over the picture.

   Drawn on the footage rather than on a still of the screen, because the crop
   is an export setting: what you want to frame is what you actually recorded,
   and you can change your mind about it afterwards."
  [take]
  (let [_    @video-tick                     ; re-render when the picture moves
        crop (:crop take)
        g    (video-geometry)
        inv  (when g (/ 1 (:scale g)))
        d    @crop-drag]
    [:div.crop-layer
     {:class (when @crop-mode "drawing")
      :style (when g {:left (:left g) :top (:top g)
                      :width (:w g) :height (:h g)})
      :on-mouse-down
      (fn [e]
        (when @crop-mode
          (let [r (.getBoundingClientRect (.-currentTarget e))]
            (reset! crop-drag {:x0 (- (.-clientX e) (.-left r))
                               :y0 (- (.-clientY e) (.-top r))
                               :x1 (- (.-clientX e) (.-left r))
                               :y1 (- (.-clientY e) (.-top r))}))))
      :on-mouse-move
      (fn [e]
        (when (and @crop-mode @crop-drag)
          (let [r (.getBoundingClientRect (.-currentTarget e))]
            (swap! crop-drag assoc
                   :x1 (- (.-clientX e) (.-left r))
                   :y1 (- (.-clientY e) (.-top r))))))
      :on-mouse-up
      (fn [_]
        (when-let [dr @crop-drag]
          (reset! crop-drag nil)
          (reset! crop-mode false)
          (when-let [g (video-geometry)]
            (let [c (rect-from-drag dr (:scale g))]
              ;; A stray click is not an area. Anything this small is a misfire.
              (when (and (> (:w c) 24) (> (:h c) 24))
                (state/set-crop! (:id take) c))))))}
     ;; the box being dragged right now
     (when d
       (let [x (min (:x0 d) (:x1 d)) y (min (:y0 d) (:y1 d))
             w (js/Math.abs (- (:x1 d) (:x0 d))) h (js/Math.abs (- (:y1 d) (:y0 d)))]
         [:div.crop-box {:style {:left x :top y :width w :height h}}]))
     ;; the area already set, drawn back in element coordinates
     (when (and crop inv (not d))
       [:div.crop-box.set {:style {:left   (* (:x crop) inv)
                                   :top    (* (:y crop) inv)
                                   :width  (* (:w crop) inv)
                                   :height (* (:h crop) inv)}}])]))

(defn- edited?
  "Whether the project has an arrangement of its own rather than the plain
   appended reading. True after a trim, a replace or an insert — all three are
   the same kind of thing to undo."
  [take]
  (boolean (or (seq (:clips take))
               (some #(or (:out %) (:dropped %)) (:segments take)))))

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
            ver   (state/version take)
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
          (when-not (or (= "empty" (:status take)) (nil? (:duration take)))
            [recorder/mode-picker])
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
                      :on-loaded-metadata (fn [_] (swap! video-tick inc))
                      :on-click (fn [_] (when-not @crop-mode (toggle!)))}]
             [crop-overlay take]]
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
             (when (edited? take)
               [:button.danger {:on-click (fn [_] (state/untrim! id))
                                :disabled (state/busy?)
                                :title "Back to plain appended sittings, in the order they were recorded. Nothing was ever deleted, so this can always be pressed."}
                "undo edits"])
             [:button {:class (when @crop-mode "recording")
                       :on-click (fn [_] (swap! crop-mode not) (reset! crop-drag nil))
                       :title "Drag a box on the picture; the export is cropped to it"}
              (if @crop-mode "drawing — drag a box" "Crop area")]
             (when (:crop take)
               [:button.danger {:on-click (fn [_] (state/clear-crop! id))} "full frame"])
             [:button {:on-click (fn [_] (state/export! id))
                       :disabled (:exporting? @state/app)}
              (if (:exporting? @state/app) "Exporting…" "Export mp4")]]
            [lanes take]
            [:div.meta-line
             [:span (:width take) "×" (:height take)]
             (let [live (count (remove #(or (:dropped %) (:pending %)) (:segments take)))
                   cl   (count (:clips take))]
               [:span (if (= 1 live) "one sitting" (str live " sittings"))
                ;; More clips than sittings means a sitting was cut in two by
                ;; an insert, which is worth seeing: it is the only place the
                ;; arrangement stops being one-piece-per-recording.
                (when (> cl live) (str ", " cl " pieces"))])
             (when-let [c (:crop take)]
               [:span "crop " (:w c) "×" (:h c) " — applied on export"])
             (when-let [db (:peak-dbfs take)]
               [:span {:style (when (< db -30) {:color "var(--record)"})}
                "peak " (js/Math.round db) " dBFS"
                (when (< db -30) " — recorded too quietly")])
             [:span [:a {:href (str "/media/" id "/audio.wav?v=" ver) :download true} "audio.wav"]]
             [:span [:a {:href (str "/media/" id "/video.mp4?v=" ver) :download true} "video.mp4"]]]])]))))
