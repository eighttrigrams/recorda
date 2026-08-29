(ns et.rec.media
  "Serving the recordings themselves.

   This exists because ring has no Range support and a <video> element is
   useless without it. Handed a plain 200 and a whole file, the browser will
   play from the beginning and nothing else: dragging the playhead does
   nothing, because seeking *is* a Range request. A forty minute take is also
   half a gigabyte, and answering `bytes=0-` by reading that into memory to
   hand it back would be its own kind of broken — so the response body is a
   stream over the window, not a copy of it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private content-types
  {"mp4"  "video/mp4"
   "wav"  "audio/wav"
   "mkv"  "video/x-matroska"
   "json" "application/json"
   "log"  "text/plain"})

(defn- content-type [^java.io.File f]
  (get content-types (str/lower-case (or (last (str/split (.getName f) #"\.")) ""))
       "application/octet-stream"))

(defn parse-range
  "The three forms a browser actually sends, resolved against a known length:
   `bytes=500-`, `bytes=0-1023`, and the suffix form `bytes=-500` meaning the
   last 500 bytes. Anything else is nil, which the caller answers whole."
  [header ^long len]
  (when-let [[_ a b] (re-find #"^bytes=(\d*)-(\d*)$" (str/trim (or header "")))]
    (let [a (when (seq a) (parse-long a))
          b (when (seq b) (parse-long b))]
      (cond
        (and a b) (when (<= a (min b (dec len))) [a (min b (dec len))])
        a         (when (< a len) [a (dec len)])
        b         (when (pos? b) [(max 0 (- len b)) (dec len)])
        :else     nil))))

(defn- ranged-stream
  "A stream over [start end] of the file, inclusive, that reports EOF at the
   end of the window rather than the end of the file.

   All three read arities are spelled out, including the one-argument form that
   InputStream would otherwise implement for us. Inside `proxy` each method
   name resolves to a single fn that receives `this` as an extra first
   argument, so an inherited read(byte[]) arrives as two args and matches no
   arity we declared — an ArityException from inside Jetty, surfacing as a 500
   with a truncated body."
  ^java.io.InputStream [^java.io.File f ^long start ^long end]
  (let [is        (java.io.FileInputStream. f)
        remaining (volatile! (inc (- end start)))]
    (.position (.getChannel is) start)
    (proxy [java.io.InputStream] []
      (read
        ([]
         (if (pos? @remaining)
           (let [b (.read is)]
             (when (not= -1 b) (vswap! remaining dec))
             b)
           -1))
        ([buf]
         (let [^bytes b buf]
           (.read ^java.io.InputStream this b 0 (alength b))))
        ([buf off len]
         (if (pos? @remaining)
           (let [n (.read is ^bytes buf (int off)
                          (int (min (long len) (long @remaining))))]
             (when (pos? n) (vswap! remaining - n))
             n)
           -1)))
      (available [] (int (min (long Integer/MAX_VALUE) (long (max 0 @remaining)))))
      (close [] (.close is)))))

(defn file-response
  "200 with the whole file, or 206 with the window the client asked for.
   Accept-Ranges goes on both — on the 200 especially, since that is the
   response that tells the browser seeking is possible at all."
  [^java.io.File f range-header]
  (if-not (and f (.exists f) (.isFile f))
    {:status 404 :headers {"Content-Type" "application/json"} :body "{\"error\":\"not found\"}"}
    (let [len (.length f)
          ct  (content-type f)]
      (if-let [[start end] (parse-range range-header len)]
        {:status  206
         :headers {"Content-Type"   ct
                   "Accept-Ranges"  "bytes"
                   "Content-Range"  (format "bytes %d-%d/%d" start end len)
                   "Content-Length" (str (inc (- end start)))
                   "Cache-Control"  "no-cache"}
         :body    (ranged-stream f start end)}
        {:status  200
         :headers {"Content-Type"   ct
                   "Accept-Ranges"  "bytes"
                   "Content-Length" (str len)
                   "Cache-Control"  "no-cache"}
         :body    (io/input-stream f)}))))
