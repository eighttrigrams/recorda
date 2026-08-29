(ns et.rec.capture
  "Starting and stopping the one ffmpeg that does the recording.

   **One process, one AVFoundation session, both streams.** The screen and the
   microphone are opened as a single input — \"5:1\" — rather than as two
   inputs, because two inputs are two device sessions with two clocks and
   nothing to tie them together. Asking for both from one session is what makes
   the gap between them a measurable constant rather than drift."
  (:require [et.rec.config :as config]
            [et.rec.devices :as devices]
            [et.rec.ff :as ff]
            [et.rec.split :as split]
            [et.rec.store :as store]
            [taoensso.telemere :as t]))

(defonce ^:private *state (atom {:status :idle}))

(defn status
  "Safe to serialise — the Process object is not part of it."
  []
  (let [{:keys [status id started-at screen mic error]} @*state]
    (cond-> {:status status}
      id         (assoc :id id)
      started-at (assoc :started-at started-at
                        :elapsed (/ (- (System/currentTimeMillis) started-at) 1000.0))
      screen     (assoc :screen screen)
      mic        (assoc :mic mic)
      error      (assoc :error error))))

(defn- capture-args [screen mic dir]
  (let [conf (config/config)]
    ["-y" "-loglevel" "warning"
     "-f" "avfoundation"
     "-capture_cursor" "1"
     "-framerate" (str (:framerate conf 30))
     ;; video:audio in one input — see the namespace docstring.
     "-i" (str (:index screen) ":" (:index mic))
     "-map" "0:v" "-c:v" "h264_videotoolbox" "-b:v" (:video-bitrate conf "6M")
     ;; The Scarlett Solo 4th gen presents four channels — XLR mic, instrument,
     ;; and a loopback pair — so recording it as-is gives a four channel file
     ;; whose other three are silence and whatever the desktop was playing.
     "-map" "0:a" "-af" (str "pan=mono|c0=c" (:mic-channel conf 0))
     "-c:a" "pcm_s16le"
     (str (java.io.File. ^java.io.File dir "capture.mkv"))]))

(defn start!
  "Begin a take. Returns the new status, or {:error …} if it cannot."
  []
  (locking *state
    (if (not= :idle (:status @*state))
      {:error (str "already " (name (:status @*state)))}
      (let [conf   (config/config)
            screen (devices/resolve-screen (:screen conf))
            mic    (devices/resolve-mic (:mic-name conf))]
        (cond
          (or (nil? screen) (:error screen))
          {:error (or (:error screen) "no screen found")}

          (nil? mic)
          {:error (str "no audio device matching \"" (:mic-name conf) "\"")}

          :else
          (let [id  (store/new-id)
                dir (doto (store/dir id) (.mkdirs))
                log (java.io.File. ^java.io.File dir "ffmpeg.log")
                p   (ff/spawn (capture-args screen mic dir) log)]
            (store/write-meta! id {:id      id
                                   :status  :recording
                                   :created-at (str (java.time.Instant/now))
                                   :title   id
                                   :screen  screen
                                   :mic     (:name mic)
                                   :mic-channel (:mic-channel conf 0)
                                   :width   (:width screen)
                                   :height  (:height screen)
                                   :edits   []})
            (reset! *state {:status :recording
                            :id id
                            :proc p
                            :started-at (System/currentTimeMillis)
                            :screen screen
                            :mic (:name mic)})
            (t/log! :info (str "recording " id " from " (:name screen)))
            (status)))))))

(defn stop!
  "End the take and split it. The split runs on another thread so the request
   that stopped the recording returns at once; the UI polls /api/status and
   watches it go :processing -> :idle."
  []
  (locking *state
    (let [{:keys [status id ^Process proc]} @*state]
      (if (not= :recording status)
        {:error "not recording"}
        (let [how (ff/quit! proc 15000)]
          (swap! *state assoc :status :processing :proc nil)
          (store/update-meta! id assoc :status :processing)
          (when (= :forced how)
            (t/log! :warn (str "ffmpeg for " id " ignored q and was killed")))
          (future
            (try
              (split/split! id)
              (catch Exception e
                (t/log! :error (str "split failed for " id ": " (.getMessage e)))
                (store/update-meta! id assoc :status :failed :error (.getMessage e)))
              (finally
                (reset! *state {:status :idle}))))
          {:status :processing :id id})))))
