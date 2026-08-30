(ns et.rec.ff
  "Thin wrappers over ffmpeg and ffprobe. Nothing here decides anything; the
   decisions live in et.rec.capture and et.rec.split."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn probe
  "One ffprobe field, as a trimmed string, or nil. `stream` is an ffprobe
   stream specifier such as \"a:0\"."
  [file stream entry]
  (let [{:keys [out exit]}
        (shell/sh "ffprobe" "-v" "error"
                  "-select_streams" stream
                  "-show_entries" entry
                  "-of" "default=nw=1:nk=1"
                  (str file))]
    (when (zero? exit)
      (let [v (str/trim out)]
        (when-not (or (str/blank? v) (= "N/A" v)) v)))))

(defn probe-double [file stream entry]
  (some-> (probe file stream entry) parse-double))

(defn duration
  "Container duration in seconds."
  [file]
  (let [{:keys [out exit]}
        (shell/sh "ffprobe" "-v" "error" "-show_entries" "format=duration"
                  "-of" "default=nw=1:nk=1" (str file))]
    (when (zero? exit) (parse-double (str/trim out)))))

(defonce ^:private keyframe-cache (atom {}))

(defn keyframes
  "Every keyframe time in a file's video stream, seconds, ascending.

   **This is the list a cut in the middle has to obey.** A stream copy can *end* anywhere
   — the frames it keeps still have the frames they reference — but it can only
   *begin* at a keyframe, so any cut something has to resume from lands on one
   of these. The capture writes one roughly every 0.4 s.

   Cached by path and modification time, which is safe here for a reason
   particular to this app: a segment's video.mp4 is written once when the
   sitting ends and never touched again. That is the whole editing model, and
   it is what makes the cache unable to go stale."
  [file]
  (let [f   (io/file (str file))
        key [(.getAbsolutePath f) (.lastModified f)]]
    (if-let [hit (get @keyframe-cache key)]
      hit
      (let [{:keys [out exit]}
            (shell/sh "ffprobe" "-v" "error"
                      "-select_streams" "v:0"
                      "-skip_frame" "nokey"
                      "-show_entries" "frame=pts_time"
                      "-of" "csv=p=0"
                      (str f))
            ts (if (zero? exit)
                 (->> (str/split-lines out)
                      (keep #(parse-double (str/replace (str/trim %) "," "")))
                      sort
                      vec)
                 [])]
        (swap! keyframe-cache assoc key ts)
        ts))))

(defn audio-start-offset
  "How far into the recording the microphone's first sample sits.

   **This is the number the whole design turns on.** The screen starts
   delivering frames immediately; the Scarlett takes about a second to come up,
   and the exact figure differs every run — 0.944 s and 1.031 s on two
   consecutive takes here. Matroska records that gap faithfully as the audio
   stream's start_time, which is precisely why capture writes an .mkv and not
   an .mp4: hand the same two streams to the MP4 muxer and the gap is silently
   discarded, sliding the voice a second ahead of the picture."
  [mkv]
  (or (probe-double mkv "a:0" "stream=start_time") 0.0))

(defn exec!
  "Run ffmpeg to completion. Returns {:ok? bool :log str}."
  [args]
  (let [{:keys [exit out err]} (apply shell/sh (concat ["ffmpeg" "-hide_banner" "-v" "error" "-y"] args))]
    {:ok? (zero? exit) :log (str out err)}))

(defn spawn
  "Start a long-running ffmpeg with its stdin held open, so it can be asked to
   stop with a `q` rather than a signal. Stderr and stdout go to `log-file`."
  [args log-file]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec (concat ["ffmpeg" "-hide_banner"] args)))
             (.redirectErrorStream true)
             (.redirectOutput (io/file log-file)))]
    (.start pb)))

(defn quit!
  "Ask ffmpeg to stop the way a person at a terminal would.

   A SIGTERM leaves the container's trailer unwritten and the take unplayable;
   `q` on stdin makes it flush and finalise, which is the difference between a
   recording and a broken file. Falls back to destroying the process only if it
   ignores the request."
  [^Process p timeout-ms]
  (try
    (doto (.getOutputStream p)
      (.write (int \q))
      (.flush))
    (catch Exception _ nil))
  (if (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    :clean
    (do (.destroyForcibly p)
        (.waitFor p 5 java.util.concurrent.TimeUnit/SECONDS)
        :forced)))
