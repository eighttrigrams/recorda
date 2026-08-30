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

   **Trimming is a number, not a deletion.** A segment carries an `:out`, and
   the file behind it keeps its full length — so a trim can always be pulled
   back out, and nothing recorded is lost until the project is deleted.

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

(defn segment-span
  "Each live segment with the assembly time it starts at and its effective
   length, so a position on the timeline can be resolved to a segment."
  [id]
  (loop [[s & more] (segments id) at 0.0 acc []]
    (if (nil? s)
      acc
      (let [len (or (:out s) (:duration s) 0.0)]
        (recur more (+ at len) (conj acc (assoc s :starts-at at :length len)))))))

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

(defn delete!
  "Remove a take and everything in it."
  [id]
  (let [d (dir id)]
    (when (and (.exists d) (.isDirectory d))
      (doseq [f (.listFiles d)] (io/delete-file f true))
      (io/delete-file d true)
      true)))
