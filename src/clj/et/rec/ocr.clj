(ns et.rec.ocr
  "Reading the text off a frame, with Vision.

   **Why an OCR and not a model that is shown the picture and asked.** The
   terms are known strings — an address, a key, a client's name, typed into a
   box by the person who wants them gone. So this is not a recognition problem
   with a judgement in it; it is a search, and a search should be repeatable,
   inspectable, and the same on Tuesday as it was on Monday. A vision model
   asked to find secrets in a screenshot answers differently each time it is
   asked and cannot say where on the frame it looked. What is wanted here is a
   box with coordinates.

   **Why Vision and not tesseract**, which this workspace already has for
   `rhizome-books`. Measured on a real 2560x1440 frame of a screencast:

       Vision      0.24 s/frame    253 words
       tesseract   0.67 s/frame    171 words

   and on the term that mattered — an address rendered in 12px grey — tesseract
   read `dang@eighttrigrams.net` where Vision read it correctly at confidence
   1.0. Both numbers point the same way, and the second one is the one that
   decides it: a redaction matching against a corrupted string is a redaction
   that does not happen. Tesseract was built for scans of paper, Vision has
   spent its life on screenshots, and this app only ever looks at screenshots.

   Being macOS-only costs nothing that was not already spent. recorda cannot
   leave the host anyway — AVFoundation for the screen, CoreAudio for the
   interface — so a third framework from the same box is not a new constraint.

   The helper itself is `scripts/ocr.swift`, compiled on demand into `target/`
   rather than committed as a binary. A binary in the repo is a thing to
   license, to keep and to explain, and this one takes under two seconds to
   build."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [taoensso.telemere :as t])
  (:import (java.io File)
           (java.lang ProcessBuilder$Redirect)))

(def ^:private source (io/file "scripts/ocr.swift"))
(def ^:private compiled (io/file "target/ocr"))

(defn available?
  "Whether this machine can do it at all. Only macOS has Vision, and only a
   machine with the command line tools can build the helper — but recorda is
   already host-and-macOS-only for the screen, so the honest failure here is a
   missing Xcode CLT and not a missing platform."
  []
  (and (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac")
       (.exists ^File source)
       (zero? (:exit (shell/sh "which" "swiftc")))))

(defn binary
  "The compiled helper, built if it is missing or older than its source.

   Throws rather than returning nil, because every caller wants the binary and
   none of them has anything sensible to do without it."
  ^File []
  (when-not (.exists ^File source)
    (throw (ex-info "scripts/ocr.swift is missing" {})))
  (when (or (not (.exists ^File compiled))
            (< (.lastModified ^File compiled) (.lastModified ^File source)))
    (io/make-parents compiled)
    (t/log! :info "recorda: building the OCR helper")
    (let [{:keys [exit err]} (shell/sh "swiftc" "-O" "-o" (str compiled) (str source))]
      (when-not (zero? exit)
        (throw (ex-info (str "could not build the OCR helper: " err) {})))))
  compiled)

;; --- reading the helper's output -------------------------------------------

(defn- parse-word [line]
  ;; Limit 8 so a tab that somehow survived in the text does not eat it. The
  ;; helper already replaces them, and this is the belt to that's braces.
  (let [[_ li x y w h conf text] (str/split line #"\t" 8)]
    {:line (parse-long li)
     :x    (parse-long x)
     :y    (parse-long y)
     :w    (parse-long w)
     :h    (parse-long h)
     :conf (or (parse-double conf) 0.0)
     :text (or text "")}))

(defn- collect
  "Turn the helper's flat rows into one entry per frame, in the order the
   frames were given. A frame with no text still gets an entry — the helper
   emits its `@` either way, which is what lets a clean frame be told apart
   from one that was never looked at."
  [lines on-frame]
  (loop [[l & more] lines, cur nil, acc []]
    (cond
      (nil? l) (if cur (conj acc cur) acc)

      (str/starts-with? l "@\t")
      (do (when cur (on-frame))
          (recur more {:path (subs l 2) :words []} (if cur (conj acc cur) acc)))

      (str/starts-with? l "w\t")
      (recur more (update cur :words conj (parse-word l)) acc)

      :else (recur more cur acc))))

(defn- run-worker
  "One helper process over one list of frames.

   Stdin is written from another thread while stdout is read on this one. A
   long take is thousands of paths, which is more than a pipe buffer holds, and
   writing them all before reading anything is the classic way to deadlock two
   processes that are each waiting for the other to drain."
  [paths on-frame]
  (let [p       (-> (ProcessBuilder. ^java.util.List [(str (binary))])
                    (.redirectError ProcessBuilder$Redirect/INHERIT)
                    (.start))
        writing (future
                  (with-open [w (io/writer (.getOutputStream p))]
                    (doseq [path paths]
                      (.write w (str path "\n")))))
        rows    (with-open [r (io/reader (.getInputStream p))]
                  (doall (collect (line-seq r) on-frame)))]
    @writing
    (.waitFor p)
    ;; The last frame's `@` has no successor to close it, so its callback fires
    ;; here rather than in `collect`.
    (when (seq rows) (on-frame))
    rows))

(def ^:private workers
  "Three, not one per core.

   Vision threads inside itself — one process already runs at about 140% CPU —
   so the processes are competing for the same units past a point. Measured on
   63 frames of a real take: one process 15.1 s, four processes 9.7 s. That is
   1.56x for 4x the processes, and the third one is where the curve flattens."
  3)

(defn read-frames
  "OCR a list of image files. Answers one `{:path :words}` per frame, in order.

   `on-frame` is called once per frame finished, on whichever worker's thread
   got there — it is for a progress counter and nothing that needs ordering."
  [files & [{:keys [on-frame] :or {on-frame (fn [])}}]]
  (let [paths (mapv str files)]
    (if (empty? paths)
      []
      (let [chunks (partition-all (max 1 (long (Math/ceil (/ (count paths)
                                                             (double workers)))))
                                  paths)
            done   (mapv #(future (run-worker % on-frame)) chunks)]
        (into [] (mapcat deref) done)))))
