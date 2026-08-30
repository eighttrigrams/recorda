(ns et.rec.assemble
  "Concatenating a project's arrangement into the files everything else plays.

   **Nothing here re-encodes.** The capture produces h264 with no B-frames and a
   keyframe roughly every 0.4 s, which makes three operations exact by stream
   copy: cutting a piece's tail, cutting its head at a keyframe, and joining
   pieces end to end. So a project of any length assembles in about the time it
   takes to read and write its bytes, and no generation of quality is ever lost
   — however many times you trim it back, record on, or splice something in.

   The asymmetry that shapes everything below: a copy can *end* anywhere,
   because every frame it keeps still has the frames it references, but it can
   only *begin* at a keyframe. A trim therefore stays frame-accurate and free.
   An insert has to resume from somewhere, so its cut lands on the nearest
   keyframe — up to about 0.2 s from where it was asked for — and the playhead
   is moved to where it actually landed rather than being told a comfortable
   lie."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rec.ff :as ff]
            [et.rec.peaks :as peaks]
            [et.rec.store :as store]))

(defn- fmt ^String [x] (format "%.6f" (double x)))

(defn- tidy
  "A time as meta.edn should hold it. Six decimals is a microsecond, far finer
   than anything here resolves, and it keeps `1.9669999999999996` — which is
   what subtracting two doubles hands you — from being what a person reads when
   they open the file."
  [x]
  (/ (Math/round (* 1.0e6 (double x))) 1.0e6))

(defn- parts-dir ^java.io.File [id]
  (doto (io/file (store/dir id) ".parts") (.mkdirs)))

(defn- clean-parts! [id]
  (let [d (parts-dir id)]
    (doseq [f (.listFiles d)] (io/delete-file f true))
    (io/delete-file d true)))

;; --- cutting ---------------------------------------------------------------

(def ^:private seek-nudge
  "How far past a keyframe to ask ffmpeg to seek.

   `-ss` before `-i` lands on the keyframe *at or before* the time given, so
   asking for the keyframe's own timestamp is right only if the number
   round-trips exactly. Two milliseconds is well inside a frame at 30 fps and
   nowhere near the next keyframe 0.4 s later, which makes the landing spot
   the same one every time regardless of how the last digit rounded."
  0.002)

(defn- snap-point
  "The keyframe nearest `t` in a segment's video. `t` unchanged if the file
   has no keyframe list to consult, which would mean ffprobe failed — better a
   cut in roughly the right place than no cut at all."
  [id seg t]
  (let [ks (ff/keyframes (store/segment-file id seg "video.mp4"))]
    (if (empty? ks)
      (double t)
      (double (apply min-key #(Math/abs (- (double %) (double t))) ks)))))

(defn- clip-parts!
  "The two files a clip contributes to the assembly.

   A clip covering its whole segment is the segment's own two files, handed
   through untouched — the common case, and the one that costs nothing.

   Anything else is cut, and **the sound is cut where the picture actually
   landed, not where the cut was asked for.** Audio has no keyframe constraint,
   so it is tempting to give it the exact time and let the video snap; that
   walks the two tracks apart by up to a keyframe interval at every edit, and
   the drift accumulates. Both get the same snapped instant, and then the audio
   is trimmed to the video's measured length so a part can never be the odd
   frame longer than its partner."
  [id i {:keys [seg in out whole?]}]
  (if whole?
    {:video (store/segment-file id seg "video.mp4")
     :audio (store/segment-file id seg "audio.wav")}
    (let [d     (parts-dir id)
          vfile (io/file d (format "%03d-video.mp4" i))
          afile (io/file d (format "%03d-audio.wav" i))
          src-v (store/segment-file id seg "video.mp4")
          src-a (store/segment-file id seg "audio.wav")
          k     (if (> (double in) 0.001) (snap-point id seg in) 0.0)
          len   (max 0.05 (- (double out) k))
          head  (if (pos? k)
                  ["-ss" (fmt (+ k seek-nudge))]
                  [])
          vres  (ff/exec! (concat head
                                  ["-i" (str src-v) "-t" (fmt len) "-c" "copy"
                                   "-avoid_negative_ts" "make_zero" (str vfile)]))]
      (when (:ok? vres)
        (let [vdur (or (ff/duration vfile) len)
              ares (ff/exec! (concat (if (pos? k) ["-ss" (fmt k)] [])
                                     ["-i" (str src-a)
                                      "-af" (str "apad=whole_dur=" (fmt vdur))
                                      "-t" (fmt vdur)
                                      "-c:a" "pcm_s16le" (str afile)]))]
          (when (:ok? ares) {:video vfile :audio afile}))))))

(defn- concat-list! [id label files]
  (let [f (io/file (parts-dir id) (str label ".txt"))]
    ;; **Absolute paths.** The concat demuxer resolves a relative entry against
    ;; the directory of the list file, not the working directory — so a
    ;; relative path here comes out prefixed with .parts/ and opens nothing.
    ;;
    ;; The single quotes are the demuxer's own quoting, with its escape for one
    ;; inside; ids are timestamps, so that part is belt and braces.
    (spit f (str/join "\n"
                      (map #(str "file '"
                                 (str/replace (.getAbsolutePath ^java.io.File %) "'" "'\\''")
                                 "'")
                           files)))
    f))

(defn- dimensions [f]
  [(some-> (ff/probe f "v:0" "stream=width") parse-long)
   (some-> (ff/probe f "v:0" "stream=height") parse-long)])

(defn assemble!
  "Rebuild video.mp4, audio.wav and peaks.json from the project's arrangement.
   Called whenever the arrangement or the segment list changes."
  [id]
  (let [cs (store/clips id)]
    (if (empty? cs)
      (do (store/update-meta! id assoc :status :empty :duration nil)
          {:ok? true :empty? true})
      (let [parts  (vec (map-indexed #(clip-parts! id %1 %2) cs))
            vparts (mapv :video parts)
            aparts (mapv :audio parts)]
        (cond
          (some nil? parts)
          (do (clean-parts! id)
              (store/update-meta! id assoc :status :failed :error "could not cut a clip")
              {:ok? false :error "could not cut a clip"})

          ;; Stream-copy concat needs identical stream parameters. A display
          ;; swapped between sittings is the way that stops being true, and it
          ;; is worth refusing clearly rather than writing a file that plays
          ;; wrong from the join onwards.
          (not (apply = (map dimensions vparts)))
          (do (clean-parts! id)
              (store/update-meta! id assoc :status :failed
                                  :error "segments have different video sizes — was the screen changed between recordings?")
              {:ok? false :error "segments have different video sizes"})

          :else
          (let [video (store/file id "video.mp4")
                audio (store/file id "audio.wav")
                vres  (ff/exec! ["-f" "concat" "-safe" "0" "-i" (str (concat-list! id "v" vparts))
                                 "-c" "copy" "-movflags" "+faststart" (str video)])
                dur   (when (:ok? vres) (ff/duration video))
                ares  (when dur
                        (ff/exec! ["-f" "concat" "-safe" "0" "-i" (str (concat-list! id "a" aparts))
                                   ;; Matched to the video's exact length, as
                                   ;; ever: two lanes of different lengths make
                                   ;; the timeline ambiguous.
                                   "-af" (str "apad=whole_dur=" (fmt dur))
                                   "-t" (fmt dur)
                                   "-c:a" "pcm_s16le" (str audio)]))]
            (clean-parts! id)
            (if-not (and (:ok? vres) (:ok? ares))
              (do (store/update-meta! id assoc :status :failed
                                      :error (str (:log vres) (:log ares)))
                  {:ok? false :error "assembly failed"})
              (let [pk (peaks/write! audio (store/file id "peaks.json"))]
                ;; Clear any error from a previous attempt: a project that now
                ;; assembles is not a failed one, and a stale message on the row
                ;; is worse than none.
                ;;
                ;; `:rev` counts assemblies, and the browser hangs its cache
                ;; busting on it. Duration used to serve, and mostly did — but a
                ;; replace-from-the-playhead can land on the same length it
                ;; started at, and then the tab goes on playing the old file.
                (store/update-meta! id #(-> (dissoc % :error)
                                            (update :rev (fnil inc 0))
                                            (merge {:status     :ready
                                                    :duration   dur
                                                    :peak-count (:count pk)
                                                    :peak-dbfs  (:peak-dbfs pk)})))
                ;; A stale export is worse than none: it looks like the project
                ;; and is not.
                (io/delete-file (store/file id "export.mp4") true)
                {:ok? true :duration dur :clips (count cs)}))))))))

;; --- editing ---------------------------------------------------------------

(defn- split-arrangement
  "The arrangement cut in two at `at` seconds on the timeline. Answers
   `[before after landed-at]`.

   `snap?` asks for the cut to land on a keyframe, and only an operation that
   something must *resume* from needs it. A trim throws the tail away, so it
   passes false and stays frame-accurate; an insert keeps the tail and has to
   start a copy at it, so it passes true and pays up to a keyframe interval.

   Whichever it is, **both halves are cut at the same instant**. Giving the
   near side the exact time and the far side a snapped one is how you get
   either a stutter or a gap at every seam."
  [id spans at snap?]
  (let [at  (double at)
        idx (count (take-while #(<= (+ (:starts-at %) (:length %)) (+ at 1.0e-6)) spans))
        cut (fn [i landed]
              [(mapv store/strip-clip (take i spans))
               (mapv store/strip-clip (drop i spans))
               landed])]
    (if (>= idx (count spans))
      (cut (count spans) (reduce + 0.0 (map :length spans)))
      (let [hit    (nth spans idx)
            offset (- at (:starts-at hit))]
        (if (<= offset 1.0e-6)
          (cut idx (:starts-at hit))
          (let [k (-> (if snap? (snap-point id (:seg hit) (+ (:in hit) offset))
                          (+ (:in hit) offset))
                      (max (:in hit))
                      (min (:out hit)))]
            (cond
              ;; The snap landed on one of the clip's own ends, so this is a
              ;; cut between clips and not inside one.
              (<= (- k (:in hit)) 1.0e-3)
              (cut idx (:starts-at hit))

              (<= (- (:out hit) k) 1.0e-3)
              (cut (inc idx) (+ (:starts-at hit) (:length hit)))

              :else
              [(conj (mapv store/strip-clip (take idx spans))
                     (store/strip-clip {:seg (:seg hit) :in (tidy (:in hit)) :out (tidy k)}))
               (into [(store/strip-clip {:seg (:seg hit) :in (tidy k) :out (tidy (:out hit))})]
                     (map store/strip-clip)
                     (drop (inc idx) spans))
               (+ (:starts-at hit) (- k (:in hit)))])))))))

(defn landing-point
  "Where an insert at `at` would actually cut, once the keyframe has had its
   say. The UI asks before recording so the playhead can be moved to the truth
   rather than claiming an accuracy the format does not have."
  [id at]
  (nth (split-arrangement id (store/clip-span id) at true) 2))

(defn trim-at!
  "Cut the project's tail at `at` seconds on the assembly timeline.

   Frame-accurate, because a tail cut needs no keyframe. Nothing is thrown
   away: the arrangement stops there and every file stays whole, so a trim can
   be undone."
  [id at]
  (let [spans (store/clip-span id)]
    (if (empty? spans)
      {:ok? false :error "nothing to trim"}
      (let [[before _ _] (split-arrangement id spans (max 0.05 (double at)) false)]
        (if (empty? before)
          {:ok? false :error "that would leave nothing"}
          (do (store/set-clips! id before)
              (assemble! id)))))))

(defn place!
  "Put a freshly finished segment into the arrangement, the way the sitting
   that produced it was started.

   **Nothing was rearranged while it recorded.** The mode was written down at
   the start and applied here, so a sitting that failed or was cancelled leaves
   the project exactly as it was — which is why replacing from the playhead is
   not a trim followed by a record. A trim is reversible, but a half-done
   operation is still a bad thing to hand someone."
  [id n op]
  (let [mode  (keyword (or (:mode op) :append))
        at    (double (or (:at op) 0.0))
        ;; The arrangement as it stood *before* this sitting. Without `:clips`
        ;; the reading is derived, and the new segment is already the last of
        ;; them — so timeline positions only mean what the caller meant once it
        ;; is taken back out.
        spans (vec (remove #(= n (:seg %)) (store/clip-span id)))
        fresh {:seg n}]
    (case mode
      :at-playhead
      (let [[before _ _] (split-arrangement id spans at false)]
        (store/set-clips! id (conj (vec before) fresh)))

      :insert
      (let [[before after _] (split-arrangement id spans at true)]
        (store/set-clips! id (vec (concat before [fresh] after))))

      ;; Append. Nothing to arrange unless an arrangement already exists — and
      ;; then it does have to be said, because the derived reading is no longer
      ;; in play and the new sitting would otherwise never be mentioned.
      (when (seq (:clips (store/read-meta id)))
        (store/set-clips! id (conj (mapv store/strip-clip spans) fresh))))))

(defn untrim!
  "Back to plain appended segments: every edit cleared, every sitting back, in
   the order they were recorded. Possible because editing only ever wrote an
   arrangement — an inserted sitting is not lost by this, it goes back to being
   the last one."
  [id]
  (store/clear-clips! id)
  (assemble! id))
