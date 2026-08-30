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

(defn- pieces
  "The project's pieces laid out on the timeline: `{:i :seg :starts-at :ends-at}`.

   The same walk the server does, so the index this hands back is the index the
   server will act on. Computed from the arrangement when there is one and from
   the sittings when there is not, so it reads the same before and after the
   first edit."
  [take]
  (let [durs (into {} (map (juxt :n :duration)) (:segments take))
        cs   (if (seq (:clips take))
               (:clips take)
               (->> (:segments take)
                    (remove :dropped) (remove :pending) (sort-by :n)
                    (map (fn [s] {:seg (:n s) :out (or (:out s) (:duration s))}))))]
    (:acc (reduce (fn [{:keys [at acc]} c]
                    (let [d   (or (get durs (:seg c)) 0)
                          in  (or (:in c) 0)
                          out (or (:out c) d)
                          end (+ at (- out in))]
                      {:at  end
                       :acc (conj acc {:i (count acc) :seg (:seg c)
                                       :in in :out out
                                       :starts-at at :ends-at end})}))
                  {:at 0 :acc []}
                  cs))))

(defn- seams
  "Every marker on the timeline: `{:i :at :added?}`.

   A join by stream copy leaves no mark you can see in the picture or hear in
   the sound, so this is the only place a project's construction is visible.

   `:added?` separates the two kinds, and the difference is not cosmetic. Two
   pieces of the *same* sitting meeting end to start are continuous material
   with a mark drawn on it, and the mark can be taken away again. Two different
   sittings meeting is the join itself — there is nothing to restore it to, and
   a menu that offered to remove it would be offering to weld unrelated
   material together.

   Marker `i` sits between piece `i` and piece `i+1`; the end of the video is
   not a marker."
  [take]
  (let [ps (vec (pieces take))]
    (vec (for [i (range (dec (count ps)))
               :let [a (nth ps i) b (nth ps (inc i))]]
           {:i i
            :at (:ends-at a)
            :added? (and (= (:seg a) (:seg b))
                         (< (js/Math.abs (- (:out a) (:in b))) 0.001))}))))

(defn- piece-at
  "The piece a point on the timeline falls in."
  [take t]
  (first (filter #(and (>= t (:starts-at %)) (< t (:ends-at %))) (pieces take))))

;; The piece menu. Right-click a lane and it opens on the piece under the
;; pointer — deliberately the app's own and not the browser's, because the one
;; thing it must show is *which* piece is about to go, and a system menu cannot
;; highlight anything behind it.
(defonce ^:private piece-menu (r/atom nil))
(defonce ^:private _close-menu
  (do (.addEventListener js/document "click" #(reset! piece-menu nil))
      (.addEventListener js/document "keydown"
                         #(when (= "Escape" (.-key %)) (reset! piece-menu nil)))))

(defn- menu-point
  "Where to put a menu, in the lanes' own coordinates.

   **Not the viewport's.** The menu used to be `position: fixed` at the
   pointer's clientX/clientY, which is right everywhere except here: the panel
   around it carries a `backdrop-filter`, and that makes an element the
   containing block for any fixed descendant. So viewport coordinates were read
   against the panel and the menu opened a panel's width away from the click.

   Measured against `.lanes` and placed inside it, which is a positioned box
   either way, so the two cannot disagree again."
  [e]
  (when-let [lanes (.closest (.-target e) ".lanes")]
    (let [r (.getBoundingClientRect lanes)]
      {:x (- (.-clientX e) (.-left r))
       :y (- (.-clientY e) (.-top r))
       :w (.-width r)})))

(defn- open-piece-menu! [take e]
  (.preventDefault e)
  (.stopPropagation e)
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        frac (/ (- (.-clientX e) (.-left rect)) (.-width rect))
        dur  (or (:duration take) 0)
        t    (* (max 0 (min 0.999 frac)) dur)
        pt   (menu-point e)]
    (when-let [p (piece-at take t)]
      ;; Carries the project it was opened on. A menu is an *index*, and an
      ;; index means nothing against a different project — so switching while it
      ;; is open must not leave a live button pointing at someone else's piece.
      (reset! piece-menu (merge p pt {:kind :piece :id (:id take)
                                      :confirm? false})))))

(defn- open-seam-menu! [take seam e]
  (.preventDefault e)
  (.stopPropagation e)
  (reset! piece-menu (merge seam (menu-point e)
                            {:kind :seam :id (:id take) :confirm? false})))

(defn- split-here! [take e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        frac (/ (- (.-clientX e) (.-left rect)) (.-width rect))
        dur  (or (:duration take) 0)]
    (reset! piece-menu nil)
    (state/split-at! (:id take) (* (max 0 (min 1 frac)) dur))))

(defn- piece-highlight
  "The extent of the piece the menu is open on, shaded inside each lane.

   The point of the whole gesture is knowing what will go, and with two seams
   close together the pointer alone does not say. It is drawn in the lane body
   rather than across the lanes because that is what the times are measured
   against."
  [take]
  (let [m   @piece-menu
        dur (or (:duration take) 0)]
    (when (and m (= :piece (:kind m)) (= (:id m) (:id take)) (pos? dur))
      [:div.piece-hi {:style {:left  (str (* 100 (/ (:starts-at m) dur)) "%")
                              :width (str (* 100 (/ (- (:ends-at m) (:starts-at m)) dur)) "%")}}])))

(defn- piece-menu-view
  "The app's own menu, not the browser's.

   The reason it is not the system one is the highlight: what a delete needs
   above all is for you to see *which* piece is about to go, and a system menu
   cannot shade anything behind it.

   Two presses for anything destructive. It is still undoable — `undo edits`
   puts the whole arrangement back and nothing has left the disk — but the
   moment of pressing should not be the moment you find that out."
  [take]
  (when-let [m (let [m @piece-menu] (when (= (:id m) (:id take)) m))]
    (let [n-pieces (count (pieces take))
          seam?    (= :seam (:kind m))
          ;; A marker between two different sittings is the join, not something
          ;; anybody added, so there is nothing to take away.
          fixed?   (and seam? (not (:added? m)))]
      [:div.piece-menu {;; Above the pointer and centred on it, so it never
                        ;; covers the thing it is talking about. Clamped by
                        ;; half its own minimum width so an edge click does not
                        ;; push it out of the panel.
                        :style {:left (max 118 (min (- (or (:w m) 9999) 118)
                                                    (:x m)))
                                :top  (- (:y m) 10)}
                        ;; The document-level listener closes this; without
                        ;; stopping the bubble it would close on its own buttons.
                        :on-click #(.stopPropagation %)
                        :on-context-menu #(.preventDefault %)}
       [:div.piece-menu-head
        (if seam?
          [:span "Marker at " (fmt (:at m))]
          [:span "Piece " (inc (:i m)) " of " n-pieces])
        [:span.piece-menu-sub
         (cond
           fixed? "where two sittings meet"
           seam?  "a marker you put here"
           :else  (str "sitting " (:seg m) " · "
                       (fmt (:starts-at m)) "–" (fmt (:ends-at m))))]]
       (cond
         fixed?
         [:div.piece-menu-note
          "This is the join between two sittings, not a marker — there is
           nothing to restore it to. Delete a piece instead."]

         (:confirm? m)
         [:div.piece-menu-confirm
          [:span (if seam? "Remove it?" "Delete it?")]
          [:button.danger
           {:on-click (fn [_]
                        (if seam?
                          (state/delete-seam! (:id take) (:i m))
                          (state/delete-clip! (:id take) (:i m)))
                        (reset! piece-menu nil))}
           (if seam? "yes, remove" "yes, delete")]
          [:button {:on-click #(swap! piece-menu assoc :confirm? false)} "cancel"]]

         :else
         [:button.piece-menu-item
          {:disabled (or (state/busy?) (and (not seam?) (< n-pieces 2)))
           :title (if seam?
                    "Rejoins the two pieces either side. Nothing about what plays changes."
                    (if (< n-pieces 2)
                      "There is only one piece — trim it, or delete the project"
                      "Stops this piece being played. The sitting behind it stays whole on disk."))
           :on-click #(swap! piece-menu assoc :confirm? true)}
          (if seam? "Remove this marker" "Delete this piece")])])))

(defn- gain-slider
  "How loud a lane is. Heard immediately, written down on release.

   The number does both jobs — preview and export — because a balance you set
   by ear and then find undone in the finished file would be worse than no
   slider at all. The range goes past unity because a voice recorded quietly is
   the commonest thing to have to fix here."
  [take k label]
  (let [id (:id take)
        v  (double (or (get take (case k :voice :voice-gain :fx :fx-gain :music-gain)) 1.0))
        commit (fn [e] (state/set-gain! id k (js/parseFloat (.. e -target -value)) false))]
    [:label.gain {:class (when (= 1.0 v) "unity")}
     [:span.gain-label label]
     [:input {:type "range" :min 0 :max 2 :step 0.01 :value v
              :on-change   #(state/set-gain! id k (js/parseFloat (.. % -target -value)) true)
              :on-mouse-up commit
              :on-key-up   commit}]
     [:span.gain-read (str (js/Math.round (* 100 v)) "%")]]))

(defn- levels [take]
  [:div.levels
   [gain-slider take :voice "Voice"]
   [gain-slider take :music "Music"]
   [gain-slider take :fx    "FX"]])

;; --- the imported lanes ----------------------------------------------------
;;
;; Two of them, and they differ only in which slider they answer to — which is
;; the whole point of having both. A bed and a door slam want completely
;; different levels, and one lane means choosing between them.

(defonce ^:private music-drag (r/atom nil))
(defonce ^:private music-menu (r/atom nil))
(defonce ^:private _close-music-menu
  (.addEventListener js/document "click" #(reset! music-menu nil)))

(defn- lane-seconds-per-px
  "One CSS pixel of a lane, as a fraction of the whole timeline. Multiplied by
   the take's duration it turns a drag in pixels into a drag in seconds."
  [el]
  (let [w (.-clientWidth el)]
    (if (pos? w) (/ 1 w) 0)))

(defn- clip-lane [c] (keyword (or (:lane c) :music)))
(defn- clip-out  [c] (double (or (:out c) (:duration c) 0)))

(defn- audio-clip-view
  "One imported clip, drawn where it sits and for as long as it plays.

   Its body drags it along the lane; the grip on its right edge shortens it.
   Both are committed on release rather than as they move, so the clip follows
   the hand at screen rate and one PUT lands at the end of the gesture instead
   of one per pixel."
  [take dur clip]
  (let [d    @music-drag
        me?  (= (:cid d) (:id clip))
        at   (if (and me? (= :move (:mode d))) (:at d) (:at clip))
        len  (if (and me? (= :resize (:mode d))) (:out d) (clip-out clip))
        full (double (or (:duration clip) len))
        past (> (+ at len) dur)]
    [:div.music-clip
     {:class (str (when me? "dragging ") (when past "overhangs "))
      :style {:left  (str (* 100 (/ at (max 0.001 dur))) "%")
              :width (str (* 100 (/ len (max 0.001 dur))) "%")}
      :title (str (:name clip) " — at " (fmt at) ", plays " (fmt len)
                  (when (< len (- full 0.01))
                    (str " of " (fmt full) "; drag the end right to bring it back"))
                  (when past " — runs past the end of the video"))
      :on-mouse-down
      (fn [e]
        (.preventDefault e)
        (.stopPropagation e)
        (when-let [lane (.closest (.-target e) ".lane-body")]
          (reset! music-drag {:cid (:id clip) :mode :move
                              :at (:at clip) :out (clip-out clip)
                              :x0 (.-clientX e) :at0 (:at clip) :out0 (clip-out clip)
                              :full full
                              :per (lane-seconds-per-px lane)})))
      :on-context-menu
      (fn [e]
        (.preventDefault e)
        (.stopPropagation e)
        (reset! music-menu (merge {:cid (:id clip) :name (:name clip)
                                   :id (:id take) :confirm? false}
                                  (menu-point e))))
      :on-click #(.stopPropagation %)
      :on-double-click #(.stopPropagation %)}
     [:span.music-name (:name clip)]
     ;; The grip. Its own mousedown, and it stops the bubble so the body's
     ;; move-drag never starts underneath it.
     [:div.music-grip
      {:title "Drag to shorten. Drag back out to restore it — the file is never what gets shortened."
       :on-mouse-down
       (fn [e]
         (.preventDefault e)
         (.stopPropagation e)
         (when-let [lane (.closest (.-target e) ".lane-body")]
           (reset! music-drag {:cid (:id clip) :mode :resize
                               :at (:at clip) :out (clip-out clip)
                               :x0 (.-clientX e) :at0 (:at clip) :out0 (clip-out clip)
                               :full full
                               :per (lane-seconds-per-px lane)})))}]]))

(defn- music-menu-view [take]
  (when-let [m (let [m @music-menu] (when (= (:id m) (:id take)) m))]
    [:div.piece-menu {:style {:left (max 118 (min (- (or (:w m) 9999) 118) (:x m)))
                              :top  (- (:y m) 10)}
                      :on-click #(.stopPropagation %)
                      :on-context-menu #(.preventDefault %)}
     [:div.piece-menu-head "Clip"
      [:span.piece-menu-sub (:name m)]]
     (if (:confirm? m)
       [:div.piece-menu-confirm
        [:span "Remove it?"]
        [:button.danger {:on-click (fn [_] (state/delete-music! (:id take) (:cid m))
                                     (reset! music-menu nil))}
         "yes, remove"]
        [:button {:on-click #(swap! music-menu assoc :confirm? false)} "cancel"]]
       [:button.piece-menu-item
        {:title "Takes the clip out of the lane and its file off the disk. Unlike a sitting, this was imported — the copy that matters is the one you imported it from."
         :on-click #(swap! music-menu assoc :confirm? true)}
        "Remove this clip"])]))

(defn- audio-lane [take dur lane label hint]
  (let [id (:id take)
        cs (filter #(= lane (clip-lane %)) (:music take))]
    [:div.lane {:class (str "lane-import lane-" (name lane))}
     [:div.lane-label
      [:span label]
      [:label.add-music {:title "Import an audio file. It lands where the playhead is."}
       "+"
       [:input {:type "file" :accept "audio/*" :style {:display "none"}
                :on-change (fn [e]
                             (when-let [f (aget (.-files (.-target e)) 0)]
                               (state/add-music! id f (:time @state/app) lane))
                             (set! (.-value (.-target e)) ""))}]]]
     [:div.lane-body
      {:on-click seek-from-event
       ;; Dropping is the gesture people try first, and it carries the position
       ;; with it: the clip lands where it was dropped, not where the playhead
       ;; happens to be.
       :on-drag-over (fn [e] (.preventDefault e))
       :on-drop (fn [e]
                  (.preventDefault e)
                  (let [r    (.getBoundingClientRect (.-currentTarget e))
                        frac (/ (- (.-clientX e) (.-left r)) (.-width r))
                        at   (* (max 0 (min 1 frac)) dur)]
                    (doseq [f (array-seq (.-files (.-dataTransfer e)))]
                      (state/add-music! id f at lane))))}
      (if (seq cs)
        (for [c cs] ^{:key (:id c)} [audio-clip-view take dur c])
        [:div.empty {:style {:padding "6px 8px"}}
         (if (:importing? @state/app)
           "reading the file…"
           [:span hint " — or "
            [:a.sample-link
             {:href "#"
              :title "A synthesised clip, so the lane can be tried without going to find a file"
              :on-click (fn [e] (.preventDefault e) (.stopPropagation e)
                          (state/add-sample-music! id (:time @state/app) lane))}
             "add a sample"]])])]]))

;; Dragging is tracked on the window rather than the clip, so the pointer can
;; leave the lane mid-gesture without the clip sticking where it was abandoned.
(defonce ^:private _music-drag-move
  (.addEventListener js/window "mousemove"
    (fn [e]
      (when-let [d @music-drag]
        (let [dur (or (:duration (state/selected-take)) 0)
              dt  (* (- (.-clientX e) (:x0 d)) (:per d) dur)]
          (if (= :resize (:mode d))
            ;; Clamped to the file's own length, which is the whole of what
            ;; makes this reversible: the end stops growing exactly where the
            ;; material runs out.
            (swap! music-drag assoc :out (max 0.25 (min (:full d) (+ (:out0 d) dt))))
            (swap! music-drag assoc :at (max 0 (+ (:at0 d) dt)))))))))

(defonce ^:private _music-drag-up
  (.addEventListener js/window "mouseup"
    (fn [_]
      (when-let [d @music-drag]
        (reset! music-drag nil)
        (let [r #(/ (js/Math.round (* 1000 %)) 1000)]
          (if (= :resize (:mode d))
            (when (> (js/Math.abs (- (:out d) (:out0 d))) 0.01)
              (state/set-music! (:selected @state/app) (:cid d) {:out (r (:out d))}))
            (when (> (js/Math.abs (- (:at d) (:at0 d))) 0.01)
              (state/set-music! (:selected @state/app) (:cid d) {:at (r (:at d))}))))))))

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
      ;; Double-click marks a split. It is the counterpart of right-click:
      ;; one makes a handle, the other uses it. Both live on the lane because
      ;; the lane is where the timeline is.
      [:div.lane-body {:on-click seek-from-event
                       :on-double-click #(split-here! take %)
                       :on-context-menu #(open-piece-menu! take %)}
       [:div.video-strip]
       [piece-highlight take]]]
     [:div.lane.lane-audio
      [:div.lane-label "Audio"]
      [:div.lane-body {:on-click seek-from-event
                       :on-double-click #(split-here! take %)
                       :on-context-menu #(open-piece-menu! take %)}
       (if peaks
         [waveform (:peaks peaks)]
         [:div.empty {:style {:padding "8px"}} "reading waveform…"])
       [piece-highlight take]]]
     [audio-lane take dur :music "Music" "drop an audio file here, or press +"]
     [audio-lane take dur :fx    "FX"    "drop an effect here, or press +"]
     ;; Positioned exactly the way the playhead is, so a seam and the playhead
     ;; sitting on the same instant land on the same pixel.
     (when (pos? dur)
       (for [{:keys [i at added?]} (seams take)]
         ^{:key (str i "-" at)}
         [:div.seam {:class (when added? "added")
                     :style {:left (str "calc(58px + (100% - 58px) * " (/ at dur) ")")}}
          ;; The head is the only part that takes a pointer — the line itself
          ;; stays out of the way so it never intercepts a click meant for the
          ;; lane underneath it.
          [:div.seam-head
           {:title (str (if added? "marker" "join") " at " (fmt at)
                        " — right-click"
                        (when-not added? " (a join cannot be removed)"))
            :on-context-menu #(open-seam-menu! take {:i i :at at :added? added?} %)
            :on-click #(.stopPropagation %)
            :on-double-click #(.stopPropagation %)}]]))
     [:div.playhead {:ref   #(reset! playhead-el %)
                     :class (when armed "armed")
                     :style {:left "58px"}}]
     [piece-menu-view take]
     [music-menu-view take]]))

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
             [:button {:class (when @crop-mode "recording")
                       :on-click (fn [_] (swap! crop-mode not) (reset! crop-drag nil))
                       :title "Drag a box on the picture; the export is cropped to it"}
              (if @crop-mode "drawing — drag a box" "Crop area")]
             (when (:crop take)
               [:button.danger {:on-click (fn [_] (state/clear-crop! id))} "full frame"])
             [:button {:on-click (fn [_] (state/export! id))
                       :disabled (:exporting? @state/app)}
              (if (:exporting? @state/app) "Exporting…" "Export mp4")]]
            [levels take]
            [lanes take]
            [:div.meta-line
             [:span (:width take) "×" (:height take)]
             (let [live (count (remove #(or (:dropped %) (:pending %)) (:segments take)))
                   cl   (count (:clips take))]
               [:span (if (= 1 live) "one sitting" (str live " sittings"))
                ;; More clips than sittings means a sitting was cut in two by
                ;; a recording at the playhead, which is worth seeing: it is
                ;; the only place the arrangement stops being
                ;; one-piece-per-sitting.
                (when (> cl live) (str ", " cl " pieces"))])
             (when-let [c (:crop take)]
               [:span "crop " (:w c) "×" (:h c) " — applied on export"])
             (when-let [db (:peak-dbfs take)]
               [:span {:style (when (< db -30) {:color "var(--record)"})}
                "peak " (js/Math.round db) " dBFS"
                (when (< db -30) " — recorded too quietly")])
             [:span [:a {:href (str "/media/" id "/audio.wav?v=" ver) :download true} "audio.wav"]]
             [:span [:a {:href (str "/media/" id "/video.mp4?v=" ver) :download true} "video.mp4"]]]])]))))
