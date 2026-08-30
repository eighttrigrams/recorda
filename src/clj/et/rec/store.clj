(ns et.rec.store
  "A project is a directory, and the directory is the database.

   A project is one video, built up over as many sittings as it takes. Each
   sitting is a **segment**, recorded whole and never modified afterwards; the
   files you play and export are an **assembly** concatenated from them.

   recordings/2026-08-29-2013-07/
     segments/001/video.mp4   one sitting, exactly as captured
     segments/001/audio.wav
     segments/002/…           the next sitting, appended
     video.mp4                the assembly — segments concatenated
     audio.wav                the assembly
     peaks.json               what the waveform lane draws
     meta.edn                 everything else, including the segment list
     ffmpeg.log               what the last capture said while it ran

   **Editing is an arrangement, not a deletion.** `:segments` is the inventory;
   `:clips` is the edit list over it, saying which piece of which segment plays
   and in what order. Every file stays whole whatever the arrangement says, so
   an edit can always be pulled back out, and nothing recorded is lost until the
   project is deleted. A project with no `:clips` reads as one clip per live
   segment, which is why nothing had to be migrated when they arrived.

   No SQLite, unlike every sibling in this workspace. There is one user, the
   rows are files, and every query this app has is `ls`. A database here would
   add a schema to migrate and a second place for the truth to live — and when
   the two disagreed, the files would still be the ones holding the video."
  (:require [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.java.io :as io]
            [et.rec.config :as config])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private id-format (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmm-ss"))

(defn new-id [] (.format (LocalDateTime/now) id-format))

(defn dir ^java.io.File [id] (io/file (config/recordings-dir) id))

(defn file ^java.io.File [id name] (io/file (dir id) name))

(defn meta-file ^java.io.File [id] (file id "meta.edn"))

(defn write-meta! [id m]
  (io/make-parents (meta-file id))
  (spit (meta-file id) (with-out-str (clojure.pprint/pprint m)))
  m)

(defn read-meta [id]
  (let [f (meta-file id)]
    (when (.exists f)
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn update-meta! [id f & args]
  (write-meta! id (apply f (read-meta id) args)))

(defn segment-dir ^java.io.File [id n]
  (io/file (dir id) "segments" (format "%03d" n)))

(defn segment-file ^java.io.File [id n name]
  (io/file (segment-dir id n) name))

(defn segments
  "The project's live segments in order: neither dropped by a trim, nor still
   pending. A segment is pending between the moment recording starts and the
   moment its audio arrives — it holds its number so that numbering is stable
   even if the sitting fails, but it is not part of the video until complete."
  [id]
  (->> (:segments (read-meta id)) (remove :dropped) (remove :pending)
       (sort-by :n) vec))

(defn next-segment-n [id]
  (inc (reduce max 0 (map :n (:segments (read-meta id))))))

(defn add-segment! [id n]
  (update-meta! id (fn [m] (update m :segments (fnil conj []) {:n n :pending true}))))

(defn complete-segment! [id n duration]
  (update-meta! id update :segments
                (fn [ss] (mapv #(if (= n (:n %))
                                  (assoc % :pending false :duration duration)
                                  %)
                               ss))))

(defn drop-segment! [id n]
  (update-meta! id update :segments (fn [ss] (filterv #(not= n (:n %)) ss))))

(defn segment-duration [id n]
  (some #(when (= n (:n %)) (:duration %)) (:segments (read-meta id))))

;; --- the arrangement -------------------------------------------------------
;;
;; `:segments` is the inventory: what was recorded, never modified. `:clips` is
;; the arrangement over it — which piece of which segment plays, in what order.
;; Keeping them apart is what lets one segment appear twice, cut at different
;; points, with the file behind it still exactly as it came out of the capture.

(defn- normalise-clip
  "A stored clip resolved against the segment it names, with both bounds
   filled in. Answers nil for a clip whose segment is gone or whose bounds
   leave nothing to play, so a broken entry drops out of the arrangement
   instead of failing the assembly."
  [live {:keys [seg in out]}]
  (when-let [s (get live seg)]
    (let [d   (double (or (:duration s) 0.0))
          in  (max 0.0 (double (or in 0.0)))
          out (min d (double (or out d)))]
      (when (> out (+ in 0.01))
        {:seg    seg
         :in     in
         :out    out
         :whole? (and (< in 0.001) (> out (- d 0.001)))}))))

(defn clips
  "The arrangement: which piece of which segment plays, and in what order.

   **A project with no `:clips` reads as one clip per live segment**, honouring
   the `:out` a trim left on it. So every project recorded before clips existed
   has an arrangement without anything being migrated, and the first edit that
   writes one *is* the migration."
  [id]
  (let [m    (read-meta id)
        segs (segments id)
        live (into {} (map (juxt :n identity)) segs)]
    (if-let [cs (seq (:clips m))]
      (into [] (keep #(normalise-clip live %)) cs)
      (into [] (keep #(normalise-clip live {:seg (:n %) :out (or (:out %) (:duration %))}))
            segs))))

(defn strip-clip
  "A clip as it is written to meta.edn: bounds only where they are not the
   segment's own, so the common case stays `{:seg 2}` and the file stays
   something a person can read."
  [{:keys [seg in out whole?]}]
  (let [r #(/ (Math/round (* 1.0e6 (double %))) 1.0e6)]
    (cond-> {:seg seg}
      (> (double (or in 0.0)) 0.001) (assoc :in (r in))
      (and out (not whole?))         (assoc :out (r out)))))

(defn set-clips!
  "Write the arrangement.

   Also clears the `:out` and `:dropped` a pre-clips trim left on the segments,
   so the two ways of saying the same thing never both exist: once there is an
   arrangement, `:segments` is inventory and nothing else."
  [id cs]
  (update-meta! id (fn [m]
                     (-> m
                         (assoc :clips (mapv #(if (:whole? %) (strip-clip %) %) cs))
                         (update :segments (fn [ss] (mapv #(dissoc % :out :dropped) ss)))))))

(defn clear-clips!
  "Back to plain appended segments. Possible at all because editing only ever
   wrote an arrangement — every file is still whole and still there."
  [id]
  (update-meta! id (fn [m]
                     (-> m
                         (dissoc :clips)
                         (update :segments (fn [ss] (mapv #(dissoc % :out :dropped) ss)))))))

(def ^:private history-depth
  "How many arrangements back you can step. An arrangement is a handful of
   small maps, so the whole history of a heavily edited project is smaller than
   one frame of its video — there is no reason to be stingy, and none to be
   unbounded either."
  50)

(defn push-history!
  "Record the arrangement as it stands, before something changes it.

   `media?` says whether the change about to happen alters what plays. Markers
   do not, so stepping back over one costs no ffmpeg — the same reason putting
   one down costs none.

   The per-segment `:out` and `:dropped` go in too. They are how a trim was
   written before `:clips` existed and the first arrangement clears them, so
   without this the one step back across that boundary would lose the trim."
  [id media?]
  (update-meta! id
                (fn [m]
                  (update m :history
                          (fn [h]
                            (vec (take-last history-depth
                                            (conj (vec h)
                                                  {:clips  (:clips m)
                                                   :cuts   (mapv #(select-keys % [:n :out :dropped])
                                                                 (:segments m))
                                                   :media? (boolean media?)}))))))))

(defn pop-history!
  "Put the last recorded arrangement back, and answer the entry so the caller
   knows whether anything has to be rebuilt."
  [id]
  (let [m (read-meta id)
        h (vec (:history m))]
    (when (seq h)
      (let [e    (peek h)
            cuts (into {} (map (juxt :n identity)) (:cuts e))]
        (write-meta!
          id
          (cond-> (assoc m :history (pop h))
            true          (update :segments
                                  (fn [ss]
                                    (mapv (fn [seg]
                                            (merge (dissoc seg :out :dropped)
                                                   (dissoc (get cuts (:n seg)) :n)))
                                          ss)))
            (:clips e)    (assoc :clips (:clips e))
            (nil? (:clips e)) (dissoc :clips)))
        e))))

(defn clip-span
  "Each clip with the assembly time it starts at and its length, so a position
   on the timeline can be resolved to a place inside a segment."
  [id]
  (loop [[c & more] (clips id) at 0.0 acc []]
    (if (nil? c)
      acc
      (let [len (- (:out c) (:in c))]
        (recur more (+ at len) (conj acc (assoc c :starts-at at :length len)))))))

(defn list-recordings
  "Newest first. A directory without a readable meta.edn is skipped rather than
   guessed at — that is either a project still being written or one whose
   assembly failed, and inventing metadata for it would hide both."
  []
  (->> (.listFiles (config/recordings-dir))
       (filter #(.isDirectory ^java.io.File %))
       (keep #(read-meta (.getName ^java.io.File %)))
       (sort-by :id)
       reverse
       vec))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (io/delete-file f true))

(defn delete!
  "Remove a take and everything in it.

   **Depth first.** This used to delete the top level and then try the
   directory, which java.io.File refuses while anything is still inside it —
   and `io/delete-file` with the silent flag swallows the refusal. So meta.edn
   went, the project vanished from the library because a directory without one
   is skipped, and every segment stayed on the disk for good. A delete that
   reports success and leaves the video behind is the worst of both."
  [id]
  (let [d (dir id)]
    (when (and (.exists d) (.isDirectory d))
      (delete-tree! d)
      (not (.exists d)))))
