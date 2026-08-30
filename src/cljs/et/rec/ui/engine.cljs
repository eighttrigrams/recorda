(ns et.rec.ui.engine
  "Playback, with the audio clock as the master.

   **Nothing here ever stretches or seeks the audio.** The take is decoded once
   into an AudioBuffer and played by an AudioBufferSourceNode, which starts at a
   scheduled instant, plays samples exactly as recorded, and cannot stall or
   flush. Whatever the picture has to do to keep up, it does — and a video frame
   forty milliseconds late is invisible where forty milliseconds of audio
   artefact is not.

   That asymmetry is the whole argument. The earlier version of this player
   slaved the audio to the video, first by seeking it and then by trimming its
   playbackRate, and both are audible: a seek flushes the decoder, and a rate
   trim runs a time-stretcher over speech for as long as it is held off 1.0.
   Audio hardware is also simply the steadiest clock a browser has.

   The position of the playhead is therefore not read off any element. It is
   computed from the audio context's own clock:

       position = offset-when-we-started + (ctx.currentTime - ctx-time-we-started-at)

   An AudioBufferSourceNode cannot be paused and cannot be restarted, only
   started once and stopped, so pausing means stopping a node and remembering
   where we were, and resuming means building a new one."
  (:require [reagent.core :as r]))

(defonce ^:private ctx (atom nil))
(defonce ^:private gain (atom nil))          ;; master
(defonce ^:private voice-gain (atom nil))
(defonce ^:private music-gain (atom nil))
(defonce ^:private source (atom nil))        ;; the voice, which owns the clock
(defonce ^:private music-sources (atom []))
(defonce ^:private music-buffers (atom {}))  ;; file -> AudioBuffer
(defonce ^:private music-clips (atom []))    ;; [{:id :file :at}]
(defonce ^:private loaded (atom nil))   ;; {:id … :buf AudioBuffer}

;; Reagent-visible playback state. `offset` is where the playhead was when the
;; current run began; ask `position` for where it is now. (cljs defonce takes
;; no docstring, hence the comment.)
(defonce state
  (r/atom {:playing? false :offset 0.0 :started-at 0.0 :ready? false :loading? false}))

(def ^:private schedule-ahead
  "How far in front of the context clock a start is scheduled. Starting at
   `currentTime` itself is a race with the audio thread — the deadline may have
   passed by the time the node is wired up, and the node then starts late by an
   unknown amount, which is exactly the offset this design exists to avoid
   having. Twenty milliseconds is comfortably ahead and imperceptible."
  0.02)

(defn- ensure-ctx! []
  (or @ctx
      (let [c  (js/AudioContext.)
            g  (.createGain c)
            vg (.createGain c)
            mg (.createGain c)]
        ;; Two lanes into one master, so a slider is a gain node and not a
        ;; number applied to samples. The voice keeps the clock either way —
        ;; music hangs off the same context and is scheduled against it, never
        ;; the other way round.
        (.connect g (.-destination c))
        (.connect vg g)
        (.connect mg g)
        (reset! gain g) (reset! voice-gain vg) (reset! music-gain mg)
        (reset! ctx c)
        c)))

(defn set-gains!
  "How loud each lane plays. The same numbers the export uses — a balance set
   by ear against the preview and then undone on the way out would be worse
   than no slider at all."
  [voice music]
  (ensure-ctx!)
  (when-let [v @voice-gain] (set! (.-value (.-gain v)) (double (or voice 1.0))))
  (when-let [m @music-gain] (set! (.-value (.-gain m)) (double (or music 1.0)))))

(defn duration [] (if-let [b (:buf @loaded)] (.-duration b) 0))

(defn position
  "Where the playhead is, in seconds, from the audio clock."
  []
  (let [{:keys [playing? offset started-at]} @state]
    (if (and playing? @ctx)
      ;; The lower clamp matters: the node is scheduled slightly ahead of the
      ;; context clock, so between wiring it up and that instant the elapsed
      ;; term is negative and the playhead would tick backwards.
      (max offset (min (duration) (+ offset (- (.-currentTime @ctx) started-at))))
      offset)))

(defn playing? [] (:playing? @state))
(defn ready? [] (:ready? @state))

(defn running?
  "True only once the audio is *actually* sounding, as opposed to scheduled.

   These are not the same instant and the gap is not small. An AudioContext
   that has just been created or resumed does not start its clock until the
   output device is ready — measured here at well over a second on a cold
   context, which is the same interface start-up latency the recording side has
   to measure on every take.

   Whoever follows this clock must wait for this, not for `playing?`. The
   picture ran during that window once: the master position was still parked at
   the offset while the video advanced on its own, so it was hard-seeked
   backwards over and over — 0.125 s, then 0.047, then 0.234, then 0.158 —
   for a second and a half before the audio caught up with it."
  []
  (let [{:keys [playing? started-at]} @state]
    (and playing? @ctx (>= (.-currentTime @ctx) started-at))))

(defn- stop-music! []
  (doseq [s @music-sources]
    (set! (.-onended s) nil)
    (try (.stop s) (catch :default _ nil)))
  (reset! music-sources []))

(defn- stop-source!
  "Drop the current nodes. The voice's onended is cleared first, because
   stopping it fires that handler and we only want the handler to mean 'the
   take ran out'. A music clip ending means nothing at all — it is not the
   clock, and there are usually others still to come."
  []
  (stop-music!)
  (when-let [s @source]
    (set! (.-onended s) nil)
    (try (.stop s) (catch :default _ nil))
    (reset! source nil)))

(defn- start-music!
  "Schedule every music clip that is still to be heard from `pos` onward.

   `pos` is where the playhead will be at `at-time`, not where it is now — the
   differ by the scheduling lead, and using the wrong one puts the music twenty
   milliseconds out from the voice.

   A clip already under way when playback starts is not skipped: it begins part
   of the way in, which is what makes seeking into the middle of a track sound
   like a seek and not like a track that failed to start."
  ;; `at-time`, never `when`. The voice's own scheduling below has called this
  ;; instant `when` since the first version and got away with it, because
  ;; nothing in that `let` body uses the macro. Here the body does, and a
  ;; parameter named `when` shadows it — so `(when ...)` compiled to a call on a
  ;; number, threw on the first press of Play, and took the whole rAF loop with
  ;; it: no music, no video, and a playhead that never moved.
  [c at-time pos]
  (reset! music-sources
          (vec (keep (fn [clip]
                       (when-let [b (get @music-buffers (:file clip))]
                         (let [at (double (or (:at clip) 0.0))
                               d  (.-duration b)]
                           (when (> (+ at d) pos)
                             (let [src (.createBufferSource c)]
                               (set! (.-buffer src) b)
                               (.connect src @music-gain)
                               (if (>= at pos)
                                 (.start src (+ at-time (- at pos)) 0)
                                 (.start src at-time (- pos at)))
                               src)))))
                     @music-clips))))

(declare pause!)

(defn play! []
  (when-let [b (:buf @loaded)]
    (let [c   (ensure-ctx!)
          pos (position)]
      ;; A context created before any gesture starts suspended; resuming is
      ;; what the click buys us.
      (.resume c)
      (stop-source!)
      (if (>= pos (- (.-duration b) 0.01))
        ;; play from the top when the playhead is already at the end, rather
        ;; than starting a node that ends immediately
        (do (swap! state assoc :offset 0.0) (play!))
        (let [src     (.createBufferSource c)
              at-time (+ (.-currentTime c) schedule-ahead)]
          (set! (.-buffer src) b)
          ;; Through the voice's own gain, not the master — otherwise the Voice
          ;; slider moves a node nothing is connected to and the export is the
          ;; only place it is heard.
          (.connect src @voice-gain)
          (set! (.-onended src) (fn [] (pause!) (swap! state assoc :offset (duration))))
          (.start src at-time pos)
          (reset! source src)
          (start-music! c at-time pos)
          (swap! state assoc :playing? true :offset pos :started-at at-time))))))

(defn- reschedule-music!
  "Put the music back in the right places without disturbing the voice.

   Dragging a clip while the take is playing has to be heard, and stopping the
   voice to achieve it would be a click in the one track that must never have
   one. Only the music nodes are rebuilt."
  []
  (when (and (:playing? @state) @ctx)
    (stop-music!)
    (start-music! @ctx
                  (+ (.-currentTime @ctx) schedule-ahead)
                  (+ (position) schedule-ahead))))

(defn set-music!
  "The music lane's clips, decoded and held.

   Keyed on the file rather than the clip, so moving one costs nothing: an
   `:at` is a number in the arrangement and the samples behind it have not
   changed."
  [id clips]
  (reset! music-clips (vec clips))
  (let [live (set (map :file clips))]
    (swap! music-buffers #(into {} (filter (fn [[k _]] (contains? live k)) %))))
  (doseq [c clips
          :when (not (contains? @music-buffers (:file c)))]
    (-> (js/fetch (str "/media/" id "/music/" (:id c) "/audio"))
        (.then #(.arrayBuffer %))
        (.then #(.decodeAudioData (ensure-ctx!) %))
        (.then (fn [b]
                 (swap! music-buffers assoc (:file c) b)
                 (reschedule-music!)))
        (.catch (fn [e] (js/console.error "recorda: could not decode music" e)))))
  (reschedule-music!))

(defn pause! []
  (let [p (position)]
    (stop-source!)
    (swap! state assoc :playing? false :offset p :started-at 0.0)))

(defn toggle! [] (if (playing?) (pause!) (play!)))

(defn seek! [t]
  (let [was (playing?)
        t   (max 0 (min (or t 0) (duration)))]
    (stop-source!)
    (swap! state assoc :playing? false :offset t :started-at 0.0)
    (when was (play!))))

(defn nudge! [dt] (seek! (+ (position) dt)))

(defn load!
  "Fetch and decode a project's audio. Decoding is done once and held, because
   decodeAudioData on every selection would be the stall this design removes.

   Keyed on a **version** as well as the id, because the assembly is rebuilt
   whenever a sitting is appended or a trim moves — same project, different
   audio, and a cache that only knew the id would go on playing the old one."
  [id version]
  (if (= [id version] (:key @loaded))
    (swap! state assoc :ready? true :loading? false)
    (do
      (stop-source!)
      (reset! loaded nil)
      (swap! state assoc :playing? false :offset 0.0 :ready? false :loading? true)
      (-> (js/fetch (str "/media/" id "/audio.wav?v=" version))
          (.then #(.arrayBuffer %))
          (.then #(.decodeAudioData (ensure-ctx!) %))
          (.then (fn [buf]
                   (reset! loaded {:key [id version] :buf buf})
                   (swap! state assoc :ready? true :loading? false)))
          (.catch (fn [e]
                    (js/console.error "recorda: could not decode audio" e)
                    (swap! state assoc :ready? false :loading? false)))))))

(defn unload! []
  (stop-source!)
  (reset! loaded nil)
  (reset! music-clips [])
  (reset! music-buffers {})
  (swap! state assoc :playing? false :offset 0.0 :ready? false :loading? false))
