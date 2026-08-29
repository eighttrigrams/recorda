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
(defonce ^:private gain (atom nil))
(defonce ^:private source (atom nil))
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
      (let [c (js/AudioContext.)
            g (.createGain c)]
        (.connect g (.-destination c))
        (reset! gain g)
        (reset! ctx c)
        c)))

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

(defn- stop-source!
  "Drop the current node. Its onended is cleared first, because stopping it
   fires that handler and we only want the handler to mean 'the take ran out'."
  []
  (when-let [s @source]
    (set! (.-onended s) nil)
    (try (.stop s) (catch :default _ nil))
    (reset! source nil)))

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
        (let [src  (.createBufferSource c)
              when (+ (.-currentTime c) schedule-ahead)]
          (set! (.-buffer src) b)
          (.connect src @gain)
          (set! (.-onended src) (fn [] (pause!) (swap! state assoc :offset (duration))))
          (.start src when pos)
          (reset! source src)
          (swap! state assoc :playing? true :offset pos :started-at when))))))

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
  "Fetch and decode a take's audio. Decoding is done once and held, because
   decodeAudioData on every selection would be the stall this design removes."
  [id]
  (if (= id (:id @loaded))
    (swap! state assoc :ready? true :loading? false)
    (do
      (stop-source!)
      (reset! loaded nil)
      (swap! state assoc :playing? false :offset 0.0 :ready? false :loading? true)
      (-> (js/fetch (str "/media/" id "/audio.wav"))
          (.then #(.arrayBuffer %))
          (.then #(.decodeAudioData (ensure-ctx!) %))
          (.then (fn [buf]
                   (reset! loaded {:id id :buf buf})
                   (swap! state assoc :ready? true :loading? false)))
          (.catch (fn [e]
                    (js/console.error "recorda: could not decode audio" e)
                    (swap! state assoc :ready? false :loading? false)))))))

(defn unload! []
  (stop-source!)
  (reset! loaded nil)
  (swap! state assoc :playing? false :offset 0.0 :ready? false :loading? false))
