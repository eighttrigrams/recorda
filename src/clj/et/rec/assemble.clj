(ns et.rec.assemble
  "Concatenating a project's segments into the files everything else plays.

   **Nothing here re-encodes.** The capture produces h264 with no B-frames and a
   keyframe roughly every 0.4 s, which makes two operations exact by stream
   copy: trimming a segment's tail, and joining segments end to end. So a
   project of any length assembles in about the time it takes to read and write
   its bytes, and no generation of quality is ever lost — however many times you
   trim it back and record on.

   That is why the editing model is *append and trim the end* rather than
   general cutting: cutting from the middle would need a keyframe at the resume
   point, and cutting from the start would snap to one. The tail does not."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rec.ff :as ff]
            [et.rec.peaks :as peaks]
            [et.rec.store :as store]))

(defn- parts-dir ^java.io.File [id]
  (doto (io/file (store/dir id) ".parts") (.mkdirs)))

(defn- clean-parts! [id]
  (let [d (parts-dir id)]
    (doseq [f (.listFiles d)] (io/delete-file f true))
    (io/delete-file d true)))

(defn- part!
  "The piece of a segment that the assembly should use. Untrimmed, that is the
   segment's own file; trimmed, a tail-cut copy of it. The segment itself is
   never written to."
  [id {:keys [n out]} kind]
  (let [src (store/segment-file id n kind)]
    (if-not (and out (pos? out))
      src
      (let [dst (io/file (parts-dir id) (str (format "%03d" n) "-" kind))
            res (ff/exec! ["-i" (str src) "-t" (format "%.6f" (double out))
                           "-c" "copy" (str dst)])]
        (when (:ok? res) dst)))))

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
  "Rebuild video.mp4, audio.wav and peaks.json from the project's live
   segments. Called whenever the segment list or a trim changes."
  [id]
  (let [segs (store/segments id)]
    (if (empty? segs)
      (do (store/update-meta! id assoc :status :empty :duration nil)
          {:ok? true :empty? true})
      (let [vparts (mapv #(part! id % "video.mp4") segs)
            aparts (mapv #(part! id % "audio.wav") segs)]
        (cond
          (some nil? (concat vparts aparts))
          (do (clean-parts! id)
              (store/update-meta! id assoc :status :failed :error "could not trim a segment")
              {:ok? false :error "could not trim a segment"})

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
                                   "-af" (str "apad=whole_dur=" (format "%.6f" dur))
                                   "-t" (format "%.6f" dur)
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
                (store/update-meta! id #(-> (dissoc % :error)
                                            (merge {:status     :ready
                                                    :duration   dur
                                                    :peak-count (:count pk)
                                                    :peak-dbfs  (:peak-dbfs pk)})))
                ;; A stale export is worse than none: it looks like the project
                ;; and is not.
                (io/delete-file (store/file id "export.mp4") true)
                {:ok? true :duration dur :segments (count segs)}))))))))

(defn trim-at!
  "Cut the project's tail at `at` seconds on the assembly timeline.

   The segment containing that instant gets an `:out`; the segments after it are
   marked dropped. Neither throws anything away — the files stay whole, so a
   trim can be undone by clearing it."
  [id at]
  (let [spans (store/segment-span id)
        hit   (or (last (filter #(<= (:starts-at %) at) spans)) (first spans))]
    (if (nil? hit)
      {:ok? false :error "nothing to trim"}
      (let [offset (max 0.05 (- at (:starts-at hit)))]
        (store/update-meta!
          id update :segments
          (fn [ss] (mapv (fn [s]
                           (cond
                             (= (:n s) (:n hit)) (assoc s :out offset :dropped false)
                             (> (:n s) (:n hit)) (assoc s :dropped true)
                             :else s))
                         ss)))
        (assemble! id)))))

(defn untrim!
  "Clear every trim and bring back every dropped segment."
  [id]
  (store/update-meta! id update :segments
                      (fn [ss] (mapv #(dissoc % :out :dropped) ss)))
  (assemble! id))
