(ns et.rec.split
  "Turning a capture into the two tracks.

   These now arrive from different places — the picture from ffmpeg, the sound
   from the browser — so this happens in two steps. `video!` runs when the
   capture stops; `finish-audio!` runs when the browser has uploaded what it
   recorded, and is where the two are lined up with each other."
  (:require [clojure.java.io :as io]
            [et.rec.assemble :as assemble]
            [et.rec.config :as config]
            [et.rec.ff :as ff]
            [et.rec.store :as store]))

(defn video!
  "A segment's capture.mkv -> its video.mp4, by **stream copy**. The frames were
   already encoded by the hardware while the sitting ran; re-encoding here would
   cost minutes and a generation of quality to achieve nothing."
  [id n]
  (let [mkv   (store/segment-file id n "capture.mkv")
        video (store/segment-file id n "video.mp4")
        res   (ff/exec! ["-i" (str mkv) "-map" "0:v" "-c" "copy"
                         "-movflags" "+faststart" (str video)])]
    (if-not (:ok? res)
      {:ok? false :error (:log res)}
      (let [d (ff/duration video)]
        {:ok? true :duration d}))))

(defn finish-audio!
  "Write a sitting's microphone recording as the segment's audio.wav, aligned to
   that segment's video, then rebuild the project's assembly.

   `lead-ms` is how long the microphone was already running before the first
   video frame of this segment was written — the browser starts its capture
   first on purpose, so that the picture never begins before the sound is
   genuinely live. That head is cut here, which is what makes the segment's
   audio start exactly where its video does."
  [id n ^java.io.File uploaded lead-ms]
  (let [video  (store/segment-file id n "video.mp4")
        audio  (store/segment-file id n "audio.wav")
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
    (io/delete-file uploaded true)
    (if-not (:ok? res)
      (do (store/drop-segment! id n)
          (store/update-meta! id assoc :status :failed :error (:log res))
          {:ok? false :error (:log res)})
      (do
        (store/complete-segment! id n dur)
        ;; The capture has served its purpose: what was in it is now in the
        ;; segment's two files, one by stream copy and one losslessly.
        (when-not (config/get-conf :keep-capture? false)
          (io/delete-file (store/segment-file id n "capture.mkv") true))
        (store/update-meta! id assoc :audio-lead lead :audio-source "browser")
        (assemble/assemble! id)))))
