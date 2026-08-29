(ns et.rec.store
  "A recording is a directory, and the directory is the database.

   recordings/2026-08-29-2013-07/
     video.mp4    picture only, stream-copied out of the capture
     audio.wav    the microphone, lossless, padded to start with the picture
     peaks.json   what the waveform lane draws
     meta.edn     everything else
     ffmpeg.log   what the capture said while it ran

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

(defn list-recordings
  "Newest first. A directory without a readable meta.edn is skipped rather
   than guessed at — that is either a take still being written or one whose
   split failed, and inventing metadata for it would hide both."
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
