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

(defn- sample-length
  "How long a sample should be for the project it is going into.

   It fits what is left of the timeline from where it is dropped, up to twenty
   seconds. A sample that overhangs the end on arrival would be demonstrating
   the wrong thing — the dashed overhang edge is worth seeing when you put it
   there, and confusing when the app did."
  [id at]
  (let [dur (or (:duration (store/read-meta id)) 0.0)
        room (- dur (double (or at 0.0)))]
    (max 6.0 (min 20.0 (if (pos? room) room 20.0)))))

(defn- sample-args
  "A short bed, synthesised rather than shipped.

   The lane is hard to try without a file to hand, and a binary in the repo is
   a thing to license, to keep and to explain. Four sine partials on a major
   triad, mixed at falling weights so the fundamental carries, with a slow
   tremolo to stop it sitting dead still and fades at both ends.

   It is plainly synthetic, and that is useful in a sample: you can hear
   exactly where it starts and stops, which is the thing you are looking at the
   lane to check."
  [^java.io.File out secs]
  (let [secs (double secs)
        ;; Scaled rather than fixed, so a six second bed is not all fade.
        fi   (min 2.5 (/ secs 4.0))
        fo   (min 3.0 (/ secs 4.0))]
    (concat
      (mapcat (fn [f] ["-f" "lavfi" "-i" (format "sine=frequency=%s:duration=%.3f" f secs)])
              ["130.81" "196.00" "261.63" "329.63"])
      ["-filter_complex"
       (str "[0]volume=0.30[a];[1]volume=0.20[b];[2]volume=0.14[c];[3]volume=0.09[d];"
            "[a][b][c][d]amix=inputs=4:normalize=0,"
            "tremolo=f=0.22:d=0.35,"
            (format "afade=t=in:st=0:d=%.3f," fi)
            (format "afade=t=out:st=%.3f:d=%.3f," (- secs fo) fo)
            "aformat=sample_rates=44100:channel_layouts=stereo")
       "-c:a" "libmp3lame" "-b:a" "128k" (str out)])))

(defn add-sample!
  "Put a synthesised bed in the lane at `at`, so the lane can be tried without
   going to find a file first."
  [id at]
  (let [tmp (java.io.File/createTempFile "recorda-sample-" ".mp3")]
    (.delete tmp)
    (let [res (ff/exec! (sample-args tmp (sample-length id at)))]
      (if-not (:ok? res)
        {:ok? false :error (str "could not make a sample: " (:log res))}
        (add! id tmp "sample bed.mp3" at)))))

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
