(ns et.rec.capture
  "Starting and stopping the screen capture.

   **ffmpeg records the picture only.** It used to record both, as one
   AVFoundation session, and that was the right idea for the wrong tool: the
   session did keep one clock, but ffmpeg's AVFoundation *audio* input drops
   roughly a 512-sample buffer ten times a second — about 11% of the timeline —
   and concatenates what survives. The result is speech with pieces spliced out
   of it, which sounds like jitter and is baked into the file.

   Measured, with nothing else holding the device: a click train played at
   exactly 100.000 ms came back at 88.5 ms and 89.7 ms. The same defect appears
   on every input device, on ffmpeg 7.1.5 and 9.0.1 alike, and is unaffected by
   thread_queue_size, channel count or drop_late_frames. Audacity records the
   same interface cleanly, which is what says the fault is ffmpeg's and not the
   hardware's.

   So the microphone is captured in the browser instead — see et.rec.ui.mic —
   which loses 91 ms in five minutes rather than 11% in every second. What is
   left here is the screen, which ffmpeg captures perfectly well."
  (:require [et.rec.assemble :as assemble]
            [et.rec.config :as config]
            [et.rec.devices :as devices]
            [et.rec.ff :as ff]
            [et.rec.split :as split]
            [et.rec.store :as store]
            [taoensso.telemere :as t]))

(defonce ^:private *state (atom {:status :idle}))

(defn status []
  (let [{:keys [status id segment mode at started-at video-started-at screen error]} @*state]
    (cond-> {:status status}
      id               (assoc :id id)
      segment          (assoc :segment segment)
      mode             (assoc :mode mode)
      at               (assoc :at at)
      started-at       (assoc :started-at started-at
                              :elapsed (/ (- (System/currentTimeMillis) started-at) 1000.0))
      video-started-at (assoc :video-started-at video-started-at)
      screen           (assoc :screen screen)
      error            (assoc :error error))))

(defn- capture-args [screen ^java.io.File dir]
  (let [conf (config/config)]
    ["-y" "-loglevel" "warning"
     "-f" "avfoundation"
     "-capture_cursor" "1"
     "-framerate" (str (:framerate conf 30))
     "-i" (str (:index screen))
     "-c:v" "h264_videotoolbox" "-b:v" (:video-bitrate conf "6M")
     (str (java.io.File. ^java.io.File dir "capture.mkv"))]))

(def ^:private first-frames-bytes
  "How much of the capture file has to exist before we call the picture
   started. At the configured bitrate this is a fraction of a second of video —
   enough that frames are genuinely being written rather than just a container
   header."
  20000)

(defn- await-first-frames!
  "Block until ffmpeg is actually writing frames, and answer the wall-clock
   instant at which that became true.

   This is the number the browser aligns its audio against, and it has to be
   measured rather than assumed: spawning ffmpeg and calling that the start
   would count its own startup — opening the display, negotiating a pixel
   format, bringing up the encoder — as recorded video that does not exist."
  [^java.io.File mkv timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (> (.length mkv) first-frames-bytes) (System/currentTimeMillis)
        (> (System/currentTimeMillis) deadline) (System/currentTimeMillis)
        :else (do (Thread/sleep 15) (recur))))))

(def modes
  "Where a sitting's material goes when it is finished.

   `:append` puts it on the end and is the default — a screencast is recorded
   roughly in order, and this is what pressing Record means most of the time.
   `:at-playhead` replaces everything from `:at` onward. `:insert` splices it in
   at `:at` and keeps what followed."
  #{:append :at-playhead :insert})

(defn start!
  "Begin recording a new **segment** of the given project.

   A project is one video built over as many sittings as it takes; this is one
   sitting. The segment is captured whole into its own directory and never
   modified afterwards — every edit happens in the arrangement, so pressing
   record can only ever add a file.

   Where the material *lands* is decided by the mode, and **written down rather
   than acted on**. Replacing from the playhead is not a trim followed by a
   record: the arrangement is left alone until the sitting has actually
   produced something, so a take that fails leaves the project untouched. For
   `:insert` the position is snapped to a keyframe here, and the snapped one is
   what comes back, so the caller can move its playhead to where the cut will
   really land.

   Returns the status, including `video-started-at` — the epoch millisecond at
   which frames actually began — so the caller can line its own audio up with
   it."
  ([project-id] (start! project-id nil))
  ([project-id {:keys [mode at]}]
   (locking *state
     (let [mode (or (modes (keyword mode)) :append)
           at   (double (or at 0.0))]
       (cond
         (not= :idle (:status @*state))
         {:error (str "already " (name (:status @*state)))}

         (nil? (store/read-meta project-id))
         {:error "no such project"}

         :else
         (let [conf   (config/config)
               screen (devices/resolve-screen (:screen conf))]
           (if (or (nil? screen) (:error screen))
             {:error (or (:error screen) "no screen found")}
             (let [at  (if (= :insert mode)
                         ;; Rounded to the microsecond: the answer goes into
                         ;; meta.edn and back to the browser, and neither wants
                         ;; to read 6.066000000000001.
                         (/ (Math/round (* 1.0e6 (assemble/landing-point project-id at))) 1.0e6)
                         at)
                   n   (store/next-segment-n project-id)
                   dir (doto (store/segment-dir project-id n) (.mkdirs))
                   mkv (java.io.File. ^java.io.File dir "capture.mkv")
                   log (java.io.File. ^java.io.File (store/dir project-id) "ffmpeg.log")
                   p   (ff/spawn (capture-args screen dir) log)
                   vat (await-first-frames! mkv 5000)]
               (store/add-segment! project-id n)
               (store/update-meta! project-id assoc
                                   :status :recording
                                   :pending-op {:mode mode :at at})
               (reset! *state {:status :recording
                               :id project-id
                               :segment n
                               :mode mode
                               :at at
                               :proc p
                               :started-at (System/currentTimeMillis)
                               :video-started-at vat
                               :screen screen})
               (t/log! :info (str "recording " project-id " segment " n
                                  " from " (:name screen)
                                  " (" (name mode)
                                  (when-not (= :append mode) (str " at " (format "%.3f" at)))
                                  ")"))
               (status)))))))))

(defn stop!
  "End the sitting and produce the segment's video. The segment then waits for
   its audio, which the browser uploads."
  []
  (locking *state
    (let [{:keys [status id segment ^Process proc]} @*state]
      (if (not= :recording status)
        {:error "not recording"}
        (let [how (ff/quit! proc 15000)]
          (swap! *state assoc :status :processing :proc nil)
          (when (= :forced how)
            (t/log! :warn (str "ffmpeg for " id " ignored q and was killed")))
          (let [res (try (split/video! id segment)
                         (catch Exception e
                           (t/log! :error (str "segment video failed for " id ": " (.getMessage e)))
                           {:ok? false :error (.getMessage e)}))]
            (swap! *state assoc :status :awaiting-audio)
            (if (:ok? res)
              {:status :awaiting-audio :id id :segment segment :duration (:duration res)}
              (do (store/drop-segment! id segment)
                  ;; The mode was an intention about a sitting that no longer
                  ;; exists. Leaving it written down would apply it to the next
                  ;; one, which is nobody's idea of what should happen.
                  (store/update-meta! id #(-> (dissoc % :pending-op)
                                              (assoc :status :failed :error (:error res))))
                  (reset! *state {:status :idle})
                  {:error (str "segment video failed: " (:error res))}))))))))

(defn audio-received!
  "Called once the browser's audio has been written and the take finished."
  []
  (locking *state (reset! *state {:status :idle})))
