(ns et.rec.split
  "Turning a capture into the two tracks.

   These now arrive from different places — the picture from ffmpeg, the sound
   from the browser — so this happens in two steps. `video!` runs when the
   capture stops; `finish-audio!` runs when the browser has uploaded what it
   recorded, and is where the two are lined up with each other."
  (:require [clojure.java.io :as io]
            [et.rec.config :as config]
            [et.rec.ff :as ff]
            [et.rec.peaks :as peaks]
            [et.rec.store :as store]))

(defn video!
  "capture.mkv -> video.mp4, by **stream copy**. The frames were already
   encoded by the hardware while the take was running; re-encoding here would
   cost minutes and a generation of quality to achieve nothing."
  [id]
  (let [mkv   (store/file id "capture.mkv")
        video (store/file id "video.mp4")
        res   (ff/exec! ["-i" (str mkv) "-map" "0:v" "-c" "copy"
                         "-movflags" "+faststart" (str video)])]
    (if-not (:ok? res)
      {:ok? false :error (:log res)}
      (let [d (ff/duration video)]
        (store/update-meta! id assoc :duration d)
        {:ok? true :duration d}))))

(defn finish-audio!
  "Write the browser's recording as audio.wav, aligned to the picture.

   `lead-ms` is how long the microphone was already running before the first
   video frame was written — the browser starts its capture first on purpose,
   so that the picture never begins before the sound is genuinely live. That
   head is cut here, which is what makes audio.wav's first sample correspond to
   video.mp4's first frame with nothing left for anything downstream to
   remember.

   The tail is padded to the video's exact length for the same reason as
   always: two lanes of different lengths make the timeline ambiguous the
   moment anything is cut from it."
  [id ^java.io.File uploaded lead-ms]
  (let [video  (store/file id "video.mp4")
        audio  (store/file id "audio.wav")
        peaksf (store/file id "peaks.json")
        dur    (or (ff/duration video) 0.0)
        lead   (/ (double (or lead-ms 0)) 1000.0)
        dur-s  (format "%.6f" dur)
        args   (if (pos? lead)
                 ;; -ss ahead of -i is an accurate seek on a WAV, and cutting
                 ;; the head is the whole alignment.
                 ["-ss" (format "%.6f" lead) "-i" (str uploaded)
                  "-af" (str "apad=whole_dur=" dur-s) "-t" dur-s
                  "-c:a" "pcm_s16le" (str audio)]
                 ;; The browser somehow started late; pay it back as silence.
                 ["-i" (str uploaded)
                  "-af" (str "adelay=" (Math/abs (long (or lead-ms 0))) ":all=1"
                             ",apad=whole_dur=" dur-s)
                  "-t" dur-s "-c:a" "pcm_s16le" (str audio)])
        res    (ff/exec! args)]
    (if-not (:ok? res)
      (do (store/update-meta! id assoc :status :failed :error (:log res))
          {:ok? false :error (:log res)})
      (let [pk (peaks/write! audio peaksf)]
        (store/update-meta! id merge
                            {:status       :ready
                             :duration     dur
                             :audio-lead   lead
                             :audio-source "browser"
                             :peak-count   (:count pk)
                             :peak-dbfs    (:peak-dbfs pk)})
        (when-not (config/get-conf :keep-capture? false)
          (io/delete-file (store/file id "capture.mkv") true))
        (io/delete-file uploaded true)
        {:ok? true :duration dur}))))
