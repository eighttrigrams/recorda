(ns et.rec.split
  "One capture in, two tracks out.

   The capture is a Matroska file holding a video stream and an audio stream
   that does not start at the same instant — the screen begins delivering
   frames at once, the interface takes about a second to wake. Splitting is
   where that gap is measured and paid off, so that everything downstream can
   treat both files as starting at zero."
  (:require [clojure.java.io :as io]
            [et.rec.config :as config]
            [et.rec.ff :as ff]
            [et.rec.peaks :as peaks]
            [et.rec.store :as store]))

(defn split!
  "Derive video.mp4 and audio.wav from capture.mkv, then peaks.json.

   The video is a **stream copy** — the frames were already encoded by the
   hardware while the take was running, and re-encoding them here would cost
   minutes and a generation of quality to achieve nothing.

   The audio is padded at both ends, not trimmed at either.

   At the head, `adelay` writes the measured gap as real silence, so the WAV's
   first sample corresponds to the video's first frame with nothing left to
   remember. The alternative — recording the offset in metadata and honouring
   it at every read — is one more thing for a future editor to get wrong.

   At the tail, `apad` and an explicit duration stretch the audio to the
   video's exact length. Stopping the capture leaves the audio a fraction of a
   second short — 0.68 s on the take this was written against — and two tracks
   of different lengths make the timeline ambiguous the moment anything is cut
   from it: an edit at the end would mean one thing for picture and another for
   sound."
  [id]
  (let [mkv    (store/file id "capture.mkv")
        video  (store/file id "video.mp4")
        audio  (store/file id "audio.wav")
        peaksf (store/file id "peaks.json")
        offset (ff/audio-start-offset mkv)
        ms     (max 0 (Math/round (* 1000.0 offset)))
        v-res  (ff/exec! ["-i" (str mkv) "-map" "0:v" "-c" "copy"
                          "-movflags" "+faststart" (str video)])
        dur    (when (:ok? v-res) (ff/duration video))
        ;; adelay with a zero argument is a filter that does nothing, and some
        ;; builds decline to construct it at all, so the no-gap case skips it.
        ;; apad's own whole_dur, not a `-t` on the output. `-t` looks like it
        ;; should bound this and silently does not when the input is the
        ;; Matroska capture — it returned the unpadded 5.378 s where 7.000 was
        ;; asked for, while the identical filter against a WAV obeyed it. Being
        ;; explicit inside the filter graph is both shorter and the version
        ;; that works.
        filt   (str (when (pos? ms) (str "adelay=" ms ":all=1,"))
                    "apad=whole_dur=" (format "%.6f" (or dur 0.0)))
        a-res  (if-not dur
                 {:ok? false :log "no video to match the audio against"}
                 (ff/exec! ["-i" (str mkv) "-map" "0:a" "-af" filt
                            "-c:a" "pcm_s16le" (str audio)]))]
    (if-not (and (:ok? v-res) (:ok? a-res))
      (do (store/update-meta! id assoc
                              :status :failed
                              :error  (str (:log v-res) (:log a-res)))
          {:ok? false})
      (let [pk (peaks/write! audio peaksf)]
        (store/update-meta! id merge
                            {:status       :ready
                             :duration     dur
                             :audio-offset offset
                             :peak-count   (:count pk)})
        ;; The capture has served its purpose: everything in it now exists in
        ;; the two files beside it, one by stream copy and one losslessly. It
        ;; is kept only if asked for, because keeping it doubles what a take
        ;; costs on disk.
        (when-not (config/get-conf :keep-capture? false)
          (io/delete-file mkv true))
        {:ok? true :duration dur}))))
