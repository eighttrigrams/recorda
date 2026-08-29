(ns et.rec.peaks
  "The numbers the waveform lane draws.

   Computed once, when a take is split, and cached as peaks.json — a twenty
   minute recording is 57 million samples and nobody wants that walked on every
   page load. The audio is decoded through ffmpeg rather than by parsing the
   WAV header here, so an imported file works the same as a captured one."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [et.rec.ff :as ff]))

(def ^:private default-buckets
  "Enough to look like a waveform at full width on a wide display, small enough
   to ship as JSON — about 40 KB either way, whether the take is one minute or
   forty. Wanting more is wanting to zoom, and zooming is an editing feature."
  8000)

(defn- decode-proc
  "ffmpeg decoding anything to raw mono 16-bit little-endian on stdout."
  ^Process [audio-file]
  (.start (doto (ProcessBuilder.
                  ^java.util.List ["ffmpeg" "-v" "error" "-i" (str audio-file)
                                   "-f" "s16le" "-ac" "1" "-"])
            (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))))

(defn compute
  "Peak absolute amplitude per bucket, as floats in 0.0–1.0.

   Peak rather than average on purpose: averaging makes a quiet passage and a
   clipped one look alike at this zoom, and the reason to glance at this lane
   at all is to see whether the mic was live and whether it was too hot."
  ([audio-file] (compute audio-file default-buckets))
  ([audio-file n-buckets]
   (let [dur   (or (ff/duration audio-file) 0.0)
         rate  (or (some-> (ff/probe audio-file "a:0" "stream=sample_rate") parse-long) 48000)
         total (max 1 (long (* dur rate)))
         per   (max 1 (long (Math/ceil (/ (double total) (double n-buckets)))))
         proc  (decode-proc audio-file)]
     (with-open [in (java.io.BufferedInputStream. (.getInputStream proc) (* 1024 1024))]
       (let [^bytes buf (byte-array 65536)]
         (loop [acc    (transient [])
                cur    0.0
                in-bkt 0
                ;; A 16-bit sample is two bytes and InputStream.read makes no
                ;; promise of returning an even number of them. Dropping the
                ;; odd byte would pair the next buffer's low byte with the
                ;; wrong high byte and stay wrong for the rest of the take, so
                ;; it is carried across the boundary instead. -1 is "none".
                carry  -1]
           (let [off (if (neg? carry) 0 1)
                 _   (when (pos? off) (aset-byte buf 0 (byte carry)))
                 n   (.read in buf off (- (alength buf) off))]
             (if (neg? n)
               (do (.waitFor proc)
                   {:duration dur
                    :peaks    (persistent! (if (pos? in-bkt) (conj! acc cur) acc))})
               (let [avail  (+ n off)
                     lim    (bit-and avail (bit-not 1))
                     carry' (if (odd? avail) (long (aget buf lim)) -1)
                     [acc' cur' bkt']
                     (loop [i 0, a acc, c (double cur), b (long in-bkt)]
                       (if (>= i lim)
                         [a c b]
                         (let [lo (bit-and (long (aget buf i)) 0xff)
                               hi (long (aget buf (unchecked-inc i)))
                               s  (bit-or (bit-shift-left hi 8) lo)
                               amp (/ (Math/abs (double s)) 32768.0)
                               c2 (if (> amp c) amp c)]
                           (if (>= (inc b) per)
                             (recur (+ i 2) (conj! a c2) 0.0 0)
                             (recur (+ i 2) a c2 (inc b))))))]
                 (recur acc' cur' bkt' carry'))))))))))

(defn write!
  "Compute and store peaks.json beside the audio."
  [audio-file out-file]
  (let [{:keys [duration peaks]} (compute audio-file)]
    (io/make-parents out-file)
    (spit out-file
          (json/generate-string
            {:duration duration
             :peaks    (mapv #(/ (Math/round (* (double %) 1000.0)) 1000.0) peaks)}))
    {:duration duration :count (count peaks)}))
