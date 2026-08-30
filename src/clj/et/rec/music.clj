(ns et.rec.music
  "The third lane: background music, free of the other two.

   The picture and the voice were recorded together and are locked together —
   that is what the whole assembly exists to preserve, and why neither can be
   moved without the other. Music is the opposite kind of thing. It was not
   recorded here, it has no instant it belongs to, and where it sits is a
   decision you make afterwards and change your mind about.

   So a music clip carries an `:at` and nothing else about time. It is a
   position on the finished timeline, in seconds, and **no edit moves it**.
   Trim the video and the music stays where you put it; the tail simply is not
   heard. That is the honest reading of `:at` as a place in the final piece
   rather than a place in the recording, and it is the freedom the lane is for.

   The files live in the project's own music/ directory and are never modified.
   Everything else about them — where they sit, how loud the lane is — is a
   number in meta.edn, like every other edit here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [et.rec.ff :as ff]
            [et.rec.peaks :as peaks]
            [et.rec.store :as store]))

(def ^:private music-buckets
  "Fewer than the voice lane gets. A music clip is drawn a fraction of the
   width of the timeline and nobody reads a drum hit off it — this is enough to
   see where a track has its loud parts and where it fades."
  1200)

(defn dir ^java.io.File [id]
  (doto (io/file (store/dir id) "music") (.mkdirs)))

(defn clips [id] (vec (:music (store/read-meta id))))

(defn clip [id cid] (first (filter #(= cid (:id %)) (clips id))))

(defn clip-file ^java.io.File [id cid]
  (when-let [c (clip id cid)] (io/file (dir id) (:file c))))

(defn peaks-file ^java.io.File [id cid]
  (when-let [c (clip id cid)] (io/file (dir id) (str (:file c) ".peaks.json"))))

(defn- safe-name
  "A filename from a filename. The uploaded name reaches disk, so it is rebuilt
   from characters that cannot mean anything to a path rather than checked for
   ones that can — there is no `..` to miss if a dot-dot cannot be spelt."
  [s]
  (let [base (-> (or s "music")
                 (str/replace #"[^A-Za-z0-9._-]" "-")
                 (str/replace #"-+" "-")
                 (str/replace #"^[.-]+" ""))]
    (if (str/blank? base) "music" (subs base 0 (min 60 (count base))))))

(defn add!
  "Take an uploaded file into the project as a music clip at `at` seconds.

   The file is stored as it arrived and never touched again — no transcode, no
   normalise. The browser decodes it for playback and ffmpeg reads it at
   export, and both are perfectly happy with whatever came off the disk; a
   conversion here would only be a second copy to keep and a generation to
   lose."
  [id ^java.io.File uploaded filename at]
  (let [n    (inc (reduce max 0 (map (fnil :n {:n 0}) (clips id))))
        cid  (str "m" n)
        fname (str (format "%03d" n) "-" (safe-name filename))
        dst  (io/file (dir id) fname)]
    (io/copy uploaded dst)
    (io/delete-file uploaded true)
    (let [dur (ff/duration dst)]
      (if-not (and dur (pos? dur))
        (do (io/delete-file dst true)
            {:ok? false :error "that file has no audio ffmpeg can read"})
        (let [pk (peaks/write! dst (io/file (dir id) (str fname ".peaks.json")) music-buckets)]
          (store/update-meta!
            id update :music (fnil conj [])
            {:id cid :n n :file fname :name (or filename fname)
             :at (max 0.0 (double (or at 0.0)))
             :duration dur :peak-count (:count pk)})
          {:ok? true :id cid :duration dur})))))

(defn move!
  "Put a clip somewhere else on the timeline. The only thing a drag changes."
  [id cid at]
  (if-not (clip id cid)
    {:ok? false :error "no such clip"}
    (do (store/update-meta!
          id update :music
          (fn [ms] (mapv #(if (= cid (:id %))
                            (assoc % :at (max 0.0 (double (or at 0.0))))
                            %)
                         ms)))
        {:ok? true})))

(defn remove!
  "Take a clip out of the lane, and its file off the disk.

   Unlike everything else here this really does delete — a music file was
   imported rather than recorded, so the copy that matters is the one you
   imported it from, and keeping a second one against a change of mind would
   only fill the project directory with tracks you decided against."
  [id cid]
  (if-let [c (clip id cid)]
    (do (io/delete-file (io/file (dir id) (:file c)) true)
        (io/delete-file (io/file (dir id) (str (:file c) ".peaks.json")) true)
        (store/update-meta! id update :music (fn [ms] (filterv #(not= cid (:id %)) ms)))
        {:ok? true})
    {:ok? false :error "no such clip"}))

(defn set-gain!
  "How loud a lane plays, and exports. 1.0 is as recorded; 0 is silent."
  [id k v]
  (let [v (max 0.0 (min 2.0 (double (or v 1.0))))]
    (store/update-meta! id assoc k v)
    {:ok? true k v}))
