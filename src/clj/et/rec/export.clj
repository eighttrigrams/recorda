(ns et.rec.export
  "Putting the two tracks back together as one file.

   recorda keeps picture and sound apart because that is what makes them
   separately editable, but nothing outside recorda wants two files. Export is
   where they become one again — and it is deliberately the *only* place that
   happens, so that the pair on disk stays the master and the mp4 stays a
   derivative you can throw away and remake.

   **This is also where editing will land.** A take's `:edits` is meant to stay
   a non-destructive list — cuts and gain moves described rather than applied —
   and replaying it belongs here, in the one step that already reads both
   tracks and writes something new. Nothing reads it yet; the argument is
   threaded through so that when it does, no caller changes."
  (:require [et.rec.ff :as ff]
            [et.rec.store :as store]))

(def ^:private audio-bitrate
  "AAC at 192k for a mono voice track is transparent, and the point of the
   export is a file other things will open. The lossless original stays in
   audio.wav, which is what an edit or a trip through a DAW should start from."
  "192k")

(defn- edit-args
  "The ffmpeg arguments an edit list implies. Empty today, which is why an
   export is currently a straight mux."
  [_edits]
  [])

(defn export!
  "Write export.mp4 for a take: video stream-copied, audio encoded to AAC.

   The video is **not** re-encoded. Its frames came off the hardware encoder
   during the capture and are already h264 in an mp4; copying them makes an
   export of a twenty minute take a few seconds of work rather than a few
   minutes, and costs no quality."
  [id]
  (let [video (store/file id "video.mp4")
        audio (store/file id "audio.wav")
        out   (store/file id "export.mp4")
        meta  (store/read-meta id)]
    (cond
      (nil? meta)          {:ok? false :error "no such recording"}
      (not (.exists video)) {:ok? false :error "this take has no video"}
      (not (.exists audio)) {:ok? false :error "this take has no audio"}
      :else
      (let [res (ff/exec! (concat
                            ["-i" (str video) "-i" (str audio)]
                            (edit-args (:edits meta))
                            ["-map" "0:v" "-map" "1:a"
                             "-c:v" "copy"
                             "-c:a" "aac" "-b:a" audio-bitrate
                             ;; The two tracks are written to the same length,
                             ;; so this only ever matters if something upstream
                             ;; went wrong — in which case stopping at the
                             ;; shorter one beats a tail of silence or a frozen
                             ;; frame.
                             "-shortest"
                             "-movflags" "+faststart"
                             (str out)]))]
        (if (:ok? res)
          {:ok? true :bytes (.length out) :duration (ff/duration out)}
          {:ok? false :error (:log res)})))))
