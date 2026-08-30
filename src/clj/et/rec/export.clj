(ns et.rec.export
  "Putting the two tracks back together as one file.

   recorda keeps picture and sound apart because that is what makes them
   separately editable, but nothing outside recorda wants two files. Export is
   where they become one again — and it is deliberately the *only* place that
   happens, so that the pair on disk stays the master and the mp4 stays a
   derivative you can throw away and remake.

   Editing does *not* land here, in the end. Cuts and splices live in the
   project's `:clips` and are resolved by et.rec.assemble, so that what you play
   is exactly what you export — an edit visible only in the export would be an
   edit you could not review. What is left for this step is the crop, which is
   genuinely an output setting: it changes the frame you hand out and not the
   footage you keep."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rec.ff :as ff]
            [et.rec.store :as store]))

(defn normalise-crop
  "Clamp a requested box to the video and make it encodable.

   **Both dimensions must be even.** h264 in 4:2:0 stores chroma at half
   resolution in each direction, so an odd width or height has no valid chroma
   plane; the encoder either refuses or silently rounds, and a silent round is
   the worse of the two."
  [{:keys [x y w h]} vid-w vid-h]
  (let [x (max 0 (min (long (or x 0)) (dec vid-w)))
        y (max 0 (min (long (or y 0)) (dec vid-h)))
        w (max 16 (min (long (or w 0)) (- vid-w x)))
        h (max 16 (min (long (or h 0)) (- vid-h y)))]
    {:x (- x (mod x 2)) :y (- y (mod y 2))
     :w (- w (mod w 2)) :h (- h (mod h 2))}))

(def ^:private audio-bitrate
  "AAC at 192k for a mono voice track is transparent, and the point of the
   export is a file other things will open. The lossless original stays in
   audio.wav, which is what an edit or a trip through a DAW should start from."
  "192k")

(defn- crop-args
  "Cropping is done **here**, on the way out, not during capture.

   Capture-time cropping would be free — the frames are being encoded anyway —
   and that is exactly why it is wrong. It bakes the decision into the recording
   before a single frame has been seen, and it cannot be changed afterwards. As
   an export setting the box stays a number you can redraw at any time, against
   the footage itself, which is the same reason a trim is a number rather than a
   deletion.

   The price is a re-encode of the video, paid only when a crop is set. The
   bitrate is scaled by how much of the frame survives, so cropping to a corner
   of the screen does not spend a full-screen budget on a quarter of the
   pixels."
  [crop src-w src-h base-bitrate]
  (when crop
    (let [ratio (/ (double (* (:w crop) (:h crop)))
                   (double (max 1 (* src-w src-h))))
          kbps  (max 800 (long (* base-bitrate ratio)))]
      ["-vf" (format "crop=%d:%d:%d:%d" (:w crop) (:h crop) (:x crop) (:y crop))
       "-c:v" "h264_videotoolbox" "-b:v" (str kbps "k")])))

(defn- audio-graph
  "The inputs and the filter that turn the voice and the music into one track.

   Answers nil when there is nothing to do — no music and both lanes at unity —
   so the ordinary export stays the two-input copy it always was and pays for
   none of this.

   `amix` with `normalize=0` because normalising is the wrong instrument here:
   it would divide every input by their number, so adding one quiet music clip
   would halve the voice. The lanes have sliders precisely so that the balance
   is a decision rather than an average. `duration=first` keeps the voice
   deciding the length, which is what makes a music clip hanging off the end
   simply not heard rather than a tail of music over nothing.

   Everything is brought to one format before mixing. The voice is mono off an
   interface and a music file is very often stereo at another sample rate, and
   amix will not mix what does not match."
  [meta ^java.io.File audio]
  (let [clips (vec (:music meta))
        vg    (double (or (:voice-gain meta) 1.0))
        mg    (double (or (:music-gain meta) 1.0))
        fmt   "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"]
    (when (or (seq clips) (not= 1.0 vg))
      (let [labels (cons "[v]" (map #(str "[m" % "]") (range (count clips))))
            chains (cons (format "[1:a]volume=%.4f,%s[v]" vg fmt)
                         (map-indexed
                           (fn [i c]
                             ;; adelay wants whole milliseconds, and `all=1` so
                             ;; it delays every channel rather than only the
                             ;; first — without it a stereo clip comes out with
                             ;; one side early.
                             (format "[%d:a]adelay=%d:all=1,volume=%.4f,%s[m%d]"
                                     (+ i 2)
                                     (long (Math/round (* 1000.0 (double (:at c 0.0)))))
                                     mg fmt i))
                           clips))]
        {:inputs (mapcat (fn [c] ["-i" (str (io/file (io/file (.getParentFile audio) "music")
                                                     (:file c)))])
                         clips)
         :filter (str (str/join ";" chains) ";"
                      (str/join "" labels)
                      (format "amix=inputs=%d:normalize=0:duration=first[aout]"
                              (inc (count clips))))}))))

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
      (let [crop  (:crop meta)
            sw    (or (some-> (ff/probe video "v:0" "stream=width") parse-long) 1)
            sh    (or (some-> (ff/probe video "v:0" "stream=height") parse-long) 1)
            ca    (crop-args crop sw sh 6000)
            ag    (audio-graph meta audio)
            res (ff/exec! (concat
                            ["-i" (str video) "-i" (str audio)]
                            (:inputs ag)
                            (if ag
                              ["-filter_complex" (:filter ag) "-map" "0:v" "-map" "[aout]"]
                              ["-map" "0:v" "-map" "1:a"])
                            (or ca ["-c:v" "copy"])
                            ["-c:a" "aac" "-b:a" audio-bitrate
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
