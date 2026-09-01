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
   edit you could not review. What is left for this step are the two genuine
   output settings: the crop, which changes the frame you hand out and not the
   footage you keep, and the redaction, which blurs named things out of it. Both
   are numbers over the recording rather than anything done to it, which is what
   makes either changeable a week later."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rec.ff :as ff]
            [et.rec.redact :as redact]
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

(defn- video-bitrate
  "What to spend on a re-encoded picture, in kbps.

   Cropping and redacting are both done **here**, on the way out, not during
   capture. Doing either during capture would be free — the frames are being
   encoded anyway — and that is exactly why it is wrong. It bakes the decision
   into the recording before a single frame has been seen, and it cannot be
   changed afterwards. As export settings they stay numbers you can redraw at
   any time, against the footage itself, which is the same reason a trim is a
   number rather than a deletion.

   The price is a re-encode, paid only when one of them is set. The budget is
   scaled by how much of the frame survives the crop, so cropping to a corner
   of the screen does not spend a full-screen budget on a quarter of the
   pixels. A redaction keeps the whole frame, and so keeps the whole budget."
  [crop src-w src-h base]
  (let [ratio (if crop
                (/ (double (* (:w crop) (:h crop)))
                   (double (max 1 (* src-w src-h))))
                1.0)]
    (max 800 (long (* base ratio)))))

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
        gain-of (fn [c] (double (or (if (= :fx (keyword (or (:lane c) :music)))
                                      (:fx-gain meta)
                                      (:music-gain meta))
                                    1.0)))
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
                             ;; atrim first, and the timestamps reset after it,
                             ;; or adelay would be delaying a stream that still
                             ;; thinks it starts where it was cut from.
                             (format "[%d:a]atrim=end=%.4f,asetpts=PTS-STARTPTS,adelay=%d:all=1,volume=%.4f,%s[m%d]"
                                     (+ i 2)
                                     (double (or (:out c) (:duration c) 0.0))
                                     (long (Math/round (* 1000.0 (double (:at c 0.0)))))
                                     (gain-of c) fmt i))
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
            red   (redact/for-export id meta crop)]
        (if-not (:ok? red)
          ;; A stale or unscanned redaction stops the export instead of being
          ;; ignored. Handing back a file that is *not* blurred where the
          ;; project says it should be is the one failure this must never have,
          ;; and it is the one failure that would look like success.
          red
          (let [sw  (or (some-> (ff/probe video "v:0" "stream=width") parse-long) 1)
                sh  (or (some-> (ff/probe video "v:0" "stream=height") parse-long) 1)
                vc  (:filter red)
                ag  (audio-graph meta audio)
                ;; Both halves of the graph go in one -filter_complex. The
                ;; video half is absent whenever nothing is being done to the
                ;; picture, and that absence is what keeps the ordinary export
                ;; a stream copy.
                fc  (str/join ";" (remove nil? [(:filter ag) vc]))
                res (ff/exec!
                      (concat
                        ["-i" (str video) "-i" (str audio)]
                        (:inputs ag)
                        (when (seq fc) ["-filter_complex" fc])
                        ["-map" (if vc "[vout]" "0:v")
                         "-map" (if ag "[aout]" "1:a")]
                        (if vc
                          ["-c:v" "h264_videotoolbox"
                           "-b:v" (str (video-bitrate crop sw sh 6000) "k")]
                          ["-c:v" "copy"])
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
              {:ok? true :bytes (.length out) :duration (ff/duration out)
               :redacted (:boxes red)}
              {:ok? false :error (:log res)})))))))
