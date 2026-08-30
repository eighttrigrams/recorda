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
   Recording at the playhead has to resume from somewhere, so its cut lands on
   the nearest keyframe — up to about 0.2 s from where it was asked for — and the playhead
   is moved to where it actually landed rather than being told a comfortable
   lie."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rec.config :as config]
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

(defn- half-frame
  "Half the interval between two frames, at the rate this app captures.

   **The frame at `out` belongs to the next piece, not this one.** `-t` with a
   stream copy keeps every packet whose timestamp is *at or before* the end it
   is given, so asking for exactly the piece's length hands back one frame too
   many — measured: 121 frames where 120 belong, and a piece 34 ms longer than
   the arrangement says it is. Every marker then grew the video by a frame, and
   they accumulate.

   Stopping half a frame short is the honest way to say \"up to but not
   including\": it always excludes the frame sitting exactly on the boundary,
   and for a cut that does not land on one it rounds to the nearest frame
   instead of always rounding up. A millisecond is not enough — the timestamps
   round at about that scale, and `-t 3.999` still kept the frame at 4.000."
  []
  (/ 0.5 (double (config/get-conf :framerate 30))))

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
          len   (max 0.05 (- (double out) k (half-frame)))
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
   passes false and stays frame-accurate; recording at the playhead keeps the
   tail and has to start a copy at it, so it passes true and pays up to a
   keyframe interval.

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
  "Where a cut at `at` would actually land, once the keyframe has had its say.
   The UI asks before recording so it can show the truth rather than claim an
   accuracy the format does not have."
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
          (do (store/push-history! id true)
              (store/set-clips! id before)
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
      (let [[before after _] (split-arrangement id spans at true)]
        (store/push-history! id true)
        (store/set-clips! id (vec (concat before [fresh] after))))

      ;; Append. Nothing to arrange unless an arrangement already exists — and
      ;; then it does have to be said, because the derived reading is no longer
      ;; in play and the new sitting would otherwise never be mentioned.
      (when (seq (:clips (store/read-meta id)))
        (store/push-history! id true)
        (store/set-clips! id (conj (mapv store/strip-clip spans) fresh))))))

(defn- coalesce
  "Adjacent pieces of the same sitting that meet end to start are one piece.

   This is what makes deleting an insertion leave no trace. Recording into the
   middle of a sitting cuts it in two; take the inserted piece away again and
   the two halves are once more continuous material with a pointless cut
   between them. Rejoining them removes a seam that would otherwise mark a join
   where nothing is joined."
  [id cs]
  (reduce (fn [acc c]
            (let [p (peek acc)]
              (if (and p (= (:seg p) (:seg c))
                       (< (Math/abs (- (double (:out p)) (double (:in c)))) 1.0e-3))
                (let [d (double (or (store/segment-duration id (:seg p)) 0.0))
                      m (assoc p :out (:out c))]
                  (conj (pop acc)
                        (assoc m :whole? (and (< (double (:in m)) 0.001)
                                              (> (double (:out m)) (- d 0.001))))))
                (conj acc c))))
          []
          cs))

(defn- plain?
  "Whether an arrangement says exactly what no arrangement would say: every
   sitting, whole, in the order it was recorded."
  [id cs]
  (and (every? :whole? cs)
       (= (mapv :seg cs) (mapv :n (store/segments id)))))

(defn delete-clip!
  "Remove one piece from the arrangement.

   The sitting behind it is untouched and stays on disk, like everything else
   here — this only stops the piece being played. So deleting the wrong one
   costs a press of `undo edits`.

   If what is left is what no arrangement would say — every sitting, whole, in
   order — the arrangement is dropped rather than written out. A project edited
   back to plain should not go on claiming to be edited."
  [id i]
  (let [cs (store/clips id)]
    (cond
      (not (< -1 i (count cs)))
      {:ok? false :error "no such piece"}

      (= 1 (count cs))
      {:ok? false :error "that is the only piece there is"}

      :else
      (let [kept (coalesce id (vec (concat (take i cs) (drop (inc i) cs))))]
        (store/push-history! id true)
        (if (plain? id kept)
          (store/clear-clips! id)
          (store/set-clips! id (mapv store/strip-clip kept)))
        (assemble! id)))))

(defn split-at!
  "Mark a split at `at` seconds: one piece becomes two, and **nothing about
   what plays changes**.

   A marker is not an edit. It is the handle an edit needs: put two of them
   round something and the piece between them can be deleted, which is how you
   cut from the middle without a mode for it.

   The cut lands on a keyframe, for the reason everything else here does — the
   second piece has to resume from it. Splitting and rejoining at a keyframe is
   exact: measured on a real recording, same duration, same frame count, and
   the audio subtracts to silence. So a project can carry any number of markers
   and still be the recording that came off the screen.

   That exactness is also why **nothing is rebuilt here**. The assembly a
   marker would produce is the assembly already on disk, so running ffmpeg over
   it would spend a second to write the same file back."
  [id at]
  (let [spans (store/clip-span id)]
    (if (empty? spans)
      {:ok? false :error "nothing to split"}
      (let [[before after landed] (split-arrangement id spans at true)]
        (if (or (empty? before) (empty? after))
          {:ok? false :error "that is already an end"}
          (do (store/push-history! id false)
              (store/set-clips! id (vec (concat before after)))
              ;; **No rebuild.** A marker does not change what plays, so the
              ;; files it would produce are the files already on disk — proven,
              ;; not assumed: nine pieces and six markers assemble to 962
              ;; frames, exactly what the unmarked original has, and the audio
              ;; subtracts to silence.
              ;;
              ;; Rebuilding anyway cost a second of ffmpeg for a no-op, which
              ;; is the whole delay between double-clicking and seeing a mark.
              ;; It also left `:rev` alone, so the browser does not re-decode
              ;; the audio or blank the waveform either.
              {:ok? true :at landed :duration (:duration (store/read-meta id))}))))))

(defn- addition?
  "Whether a marker is one somebody put there, rather than a place where the
   material genuinely changes.

   Two pieces of the *same* sitting meeting end to start are continuous
   material with a mark drawn on it, and taking the mark away restores what was
   always true. Two different sittings meeting is not a mark at all — it is the
   join, and there is nothing to restore it to."
  [a b]
  (and (= (:seg a) (:seg b))
       (< (Math/abs (- (double (:out a)) (double (:in b)))) 1.0e-3)))

(defn delete-seam!
  "Remove one marker, so the two pieces either side of it become one again."
  [id i]
  (let [cs (store/clips id)]
    (cond
      (not (< -1 i (dec (count cs))))
      {:ok? false :error "no such marker"}

      (not (addition? (nth cs i) (nth cs (inc i))))
      {:ok? false :error "that is where two sittings meet, not a marker — delete a piece instead"}

      :else
      (let [a      (nth cs i)
            b      (nth cs (inc i))
            d      (double (or (store/segment-duration id (:seg a)) 0.0))
            merged (let [m (assoc a :out (:out b))]
                     (assoc m :whole? (and (< (double (:in m)) 0.001)
                                           (> (double (:out m)) (- d 0.001)))))
            kept   (vec (concat (take i cs) [merged] (drop (+ i 2) cs)))]
        (store/push-history! id false)
        (if (plain? id kept)
          (store/clear-clips! id)
          (store/set-clips! id (mapv store/strip-clip kept)))
        ;; No rebuild, for the reason `split-at!` gives: only a marker somebody
        ;; added can be removed, and those are the ones either side of which
        ;; the material is continuous. Taking one away leaves the same video.
        {:ok? true :duration (:duration (store/read-meta id))}))))

(defn undo!
  "Step back one change to the arrangement.

   Real undo, one press per change: a trim, a marker, a deleted piece, a
   sitting recorded into the middle. Not a reset — `reset!` below is that, and
   it is a different thing worth a different button.

   Stepping back over a marker costs no ffmpeg, for the same reason putting one
   down costs none: it did not change what plays, so there is nothing to
   rebuild."
  [id]
  (if-let [e (store/pop-history! id)]
    (if (:media? e)
      (assemble! id)
      {:ok? true :duration (:duration (store/read-meta id))})
    {:ok? false :error "nothing to undo"}))
