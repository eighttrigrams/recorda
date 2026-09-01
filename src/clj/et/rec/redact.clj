(ns et.rec.redact
  "Blurring things out of the picture on the way to the mp4.

   You type the terms you do not want to hand out — an address, a key, a
   client's name — and this finds them in the frames and blurs where they are.

   **It is an export setting, like the crop, and for the same reason.** Doing it
   during capture would be free, and that is exactly what is wrong with it: it
   would bake the decision in before a single frame had been looked at, and
   nothing could take it back but recording again. Kept as a list of terms and
   a list of boxes, it can be redrawn, added to, and undone a week later, and
   the take on disk stays the master.

   The corollary is worth saying out loud, because it is the one thing about
   this feature that can bite: **only `export.mp4` is redacted.** `video.mp4`
   and the segments behind it still have every pixel that was recorded. That is
   what makes the whole thing reversible, and it means the file you hand
   somebody is the export and never the take.

   ## Over-blur is cheap, under-blur is the whole failure

   Every judgement in here leans the same way. A redaction that misses one
   frame out of nine hundred is not 99.9% of a redaction; it is a leak, and it
   is a leak nobody will notice because nobody watches their own export frame
   by frame. So:

   - the box is **padded** beyond the letters it found
   - it is **held** for a beat before and after the frames it was seen in, so a
     term scrolling into view is never bare for the samples between two looks
   - matching is **approximate**, because OCR misreads and an exact match
     against a misread is a miss
   - a scan that no longer matches the terms or the assembly is **stale**, and
     an export refuses rather than quietly using it

   ## What it cannot do

   It reads text. A term rendered as a picture, handwritten, or half scrolled
   off an edge is not text and will not be found. Neither is a face, a
   window title bar, or anything else you did not name. This narrows a
   problem; it does not close it, and the thing that closes it is you watching
   the export before you send it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [et.rec.ff :as ff]
            [et.rec.ocr :as ocr]
            [et.rec.store :as store]
            [taoensso.telemere :as t])
  (:import (java.io File)))

(def defaults
  {:fps   2.0
   :hold  0.4
   :style :blur})

;; ---------------------------------------------------------------------------
;; Matching
;;
;; Pure, and the part with the judgement in it, so it is the part with the
;; tests.

(defn- lower
  "Case-folded without changing the length.

   `clojure.string/lower-case` is the obvious thing and is wrong here: a few
   characters fold to a different number of them, and every character offset in
   this namespace is an index into a box. One char in, one char out."
  ^String [^String s]
  (let [sb (StringBuilder. (.length s))]
    (dotimes [i (.length s)]
      (.append sb (Character/toLowerCase (.charAt s i))))
    (.toString sb)))

(defn tolerance
  "How many edits a term may be misread by and still count.

   Zero for anything short: a four-letter term with one edit allowed matches
   half the screen, and blurring half the screen is its own kind of failure.
   Long terms get one edit per seven characters, which is what covers the real
   misreads seen here — `dang@eighttrigrams.net` for `dan@eighttrigrams.net` is
   a single insertion in twenty-one characters.

   Capped, because past a handful of edits a term is not being recognised, it
   is being guessed at."
  [term]
  (min 4 (quot (count term) 7)))

(defn- trigrams [^String s]
  (into #{} (map #(subs s % (+ % 3))) (range (max 0 (- (count s) 2)))))

(defn- plausible?
  "A cheap gate before the expensive search.

   The full comparison is a dynamic program over the line for every term, and
   a long take runs it a few million times. A term that can match at all must
   leave at least one of its three-character runs intact — `tol` edits break at
   most `3*tol` of the `m-2` it has, and `m - 2 - 3*tol > 0` holds everywhere
   the tolerance above allows. So a line sharing none of them cannot be a
   match, and can be skipped without looking."
  [line-trigrams ^String term]
  (or (< (count term) 3)
      (boolean (some line-trigrams (trigrams term)))))

(defn fuzzy-spans
  "Every `[start end)` in `text` that `term` matches within `tol` edits.

   Levenshtein with a free start and a free end — the first row is zeros, so an
   alignment may begin anywhere, and any column of the last row under the
   tolerance is a match ending there. A parallel table carries where each
   alignment began, which is what turns a distance into a span, and a span into
   a box.

   Overlapping spans are merged: two near-misses around the same letters are
   one thing on the screen, and blurring their union is both simpler and the
   safer direction."
  [^String text ^String term ^long tol]
  (let [n (count text) m (count term)]
    (if (or (zero? m) (zero? n))
      []
      (let [pd (int-array (inc n)) ps (int-array (inc n))
            cd (int-array (inc n)) cs (int-array (inc n))]
        (dotimes [j (inc n)] (aset pd j 0) (aset ps j j))
        (dotimes [ii m]
          (let [i (inc ii)
                tc (.charAt term ii)]
            (aset cd 0 i) (aset cs 0 0)
            (dotimes [jj n]
              (let [j    (inc jj)
                    cost (if (= tc (.charAt text jj)) 0 1)
                    a    (+ (aget pd jj) cost)      ; substitute or match
                    b    (inc (aget pd j))          ; term char not in the text
                    c    (inc (aget cd jj))]        ; extra text char
                (cond
                  (and (<= a b) (<= a c)) (do (aset cd j a) (aset cs j (aget ps jj)))
                  (<= b c)                (do (aset cd j b) (aset cs j (aget ps j)))
                  :else                   (do (aset cd j c) (aset cs j (aget cs jj))))))
            (System/arraycopy cd 0 pd 0 (inc n))
            (System/arraycopy cs 0 ps 0 (inc n))))
        (->> (range 1 (inc n))
             (keep (fn [j] (when (<= (aget pd j) tol) [(aget ps j) j (aget pd j)])))
             ;; One span per starting point, and the tightest one.
             ;;
             ;; Every column past the real end of a match is also under the
             ;; tolerance — three spare edits will happily swallow the next
             ;; three characters — so taking every column that qualifies runs
             ;; the span into the following word. The alignment that actually
             ;; found something is the one with the fewest edits, and the
             ;; shortest of those.
             (group-by first)
             vals
             (map #(first (sort-by (juxt (fn [[_ _ d]] d) (fn [[_ e _]] e)) %)))
             (sort-by first)
             (reduce (fn [acc [s e _]]
                       (let [[ps' pe'] (peek acc)]
                         (if (and ps' (<= s pe'))
                           (conj (pop acc) [ps' (max pe' e)])
                           (conj acc [s e]))))
                     [])
             vec)))))

(defn- union-box [boxes]
  (let [x0 (apply min (map :x boxes))
        y0 (apply min (map :y boxes))
        x1 (apply max (map #(+ (:x %) (:w %)) boxes))
        y1 (apply max (map #(+ (:y %) (:h %)) boxes))]
    {:x x0 :y y0 :w (- x1 x0) :h (- y1 y0)}))

(defn- words-in-span
  "The words a character span touches, which is what actually gets blurred."
  [words starts s e]
  (into []
        (keep-indexed
          (fn [i w]
            (let [ws (nth starts i)
                  we (+ ws (count (:text w)))]
              (when (and (< ws e) (> we s)) w))))
        words))

(defn line-hits
  "Where the terms are in one line of recognised words.

   The words of a line are joined with single spaces and searched as one
   string, so a term of several words is found the same way a term of one is,
   and a term straddling a space is not lost between two boxes.

   **The box returned is whole words**, never the letters of the match. If you
   ask for `eighttrigrams` and the screen says `dan@eighttrigrams.net`, the
   whole address goes — which is more than was asked for, and more is the side
   to be wrong on. It also spares this from trusting a character-level box on a
   proportional font."
  [words terms]
  (let [texts   (mapv :text words)
        starts  (vec (butlast (reductions (fn [o t] (+ o (count t) 1)) 0 texts)))
        joined  (lower (str/join " " texts))
        tris    (trigrams joined)]
    (into []
          (mapcat
            (fn [term]
              (let [term (lower (str/trim term))
                    tol  (tolerance term)]
                (when (and (seq term) (plausible? tris term))
                  (for [[s e] (fuzzy-spans joined term tol)
                        :let  [in (words-in-span words starts s e)]
                        :when (seq in)]
                    (assoc (union-box in) :term term))))))
          terms)))

(defn frame-hits
  "Every term found on one frame, as boxes in the video's own pixels."
  [words terms]
  (into [] (mapcat #(line-hits % terms)) (vals (group-by :line words))))

;; ---------------------------------------------------------------------------
;; From per-frame hits to boxes that live for a while

(defn- intersects? [a b]
  (and (< (:x a) (+ (:x b) (:w b))) (< (:x b) (+ (:x a) (:w a)))
       (< (:y a) (+ (:y b) (:h b))) (< (:y b) (+ (:y a) (:h a)))))

(defn- area [b] (* (max 0 (:w b)) (max 0 (:h b))))

(defn- overlap-area [a b]
  (* (max 0 (- (min (+ (:x a) (:w a)) (+ (:x b) (:w b))) (max (:x a) (:x b))))
     (max 0 (- (min (+ (:y a) (:h a)) (+ (:y b) (:h b))) (max (:y a) (:y b))))))

(defn tracks
  "Per-frame hits gathered into boxes with a start and an end.

   A term sitting in a window that does not move is one box for as long as it
   is there, not one per sample — which is what keeps the filter graph a
   readable size and the export quick. A term that jumps somewhere else starts
   a second box instead of stretching the first across the gap between them,
   because a rectangle covering both would blur everything in between.

   Two guards decide whether a new hit joins an open box or opens its own:

   - the boxes must **overlap**, and the union must not be much larger than
     what went into it, so a slow scroll cannot creep a box across the screen
   - the gap in time must be **at most one missed sample**, so a term that goes
     away and comes back is two boxes and the middle is left alone"
  [hits {:keys [fps]}]
  (let [step (/ 1.0 (double fps))
        gap  (* 2.5 step)]
    (->> (sort-by :t hits)
         (reduce
           (fn [open {:keys [t term] :as hit}]
             (let [b   (select-keys hit [:x :y :w :h])
                   fit (->> (map-indexed vector open)
                            (filter (fn [[_ tr]]
                                      (and (= term (:term tr))
                                           (<= (- t (:t1 tr)) gap)
                                           (intersects? tr b)
                                           (<= (area (union-box [tr b]))
                                               (* 2.5 (max (:seed tr) (area b)))))))
                            (sort-by (fn [[_ tr]] (- (overlap-area tr b))))
                            first)]
               (if fit
                 (let [[i tr] fit
                       u (union-box [tr b])]
                   (assoc open i (merge tr u {:t1 t :n (inc (:n tr))})))
                 (conj open (merge b {:term term :t0 t :t1 t
                                      :seed (area b) :n 1})))))
           [])
         vec)))

(defn- even-floor [n] (- (long n) (mod (long n) 2)))
(defn- even-ceil  [n] (let [n (long (Math/ceil (double n)))] (+ n (mod n 2))))

(defn finish
  "A track turned into the rectangle and the interval that go into ffmpeg.

   **Padded and held.** OCR boxes hug the ink, and a blur that hugs the ink
   leaves the ascenders and the shape of the word, which for something like an
   address is most of what you were hiding. The padding is a fraction of the
   text's own height, so small print gets proportionally as much cover as
   large. The hold covers the samples between two looks: at two frames a second
   there is half a second nobody looked at either side of every hit, and a term
   scrolling into view is on screen for some of it.

   Both dimensions and both offsets are made even, because a crop or an overlay
   at an odd offset in 4:2:0 has no valid chroma plane to sit on."
  [{:keys [x y w h t0 t1 term n]} {:keys [fps hold]} vid-w vid-h]
  (let [pad (max 6 (long (Math/round (* 0.4 (double h)))))
        x0  (even-floor (max 0 (- x pad)))
        y0  (even-floor (max 0 (- y pad)))
        x1  (min vid-w (+ x w pad))
        y1  (min vid-h (+ y h pad))]
    {:x    x0
     :y    y0
     :w    (min (- vid-w x0) (even-ceil (- x1 x0)))
     :h    (min (- vid-h y0) (even-ceil (- y1 y0)))
     :t0   (max 0.0 (- t0 hold))
     :t1   (+ t1 (/ 1.0 (double fps)) hold)
     :term term
     :n    n}))

;; ---------------------------------------------------------------------------
;; The scan

(def ^:private chunk-seconds
  "How much of the video is turned into frames at a time.

   All of it at once would be simpler and would put a gigabyte of JPEG on the
   disk for a twenty minute take. A minute at a time costs one extra ffmpeg
   invocation per minute and never holds more than about thirty megabytes."
  60.0)

(defonce progress
  (atom {:state :idle}))

(defn- extract-chunk!
  "One stretch of the video as numbered JPEGs. Frame k is at `start + (k-1)/fps`."
  [video ^File dir start dur fps]
  (ff/exec! ["-ss" (str start) "-t" (str dur) "-i" (str video)
             "-vf" (str "fps=" fps)
             ;; High quality on purpose. This is not something anybody looks
             ;; at; it is something an OCR looks at, and the text worth hiding
             ;; is the small text that artefacts eat first.
             "-q:v" "2"
             (str (io/file dir "%06d.jpg"))]))

(defn- rm-tree! [^File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-tree! c)))
  (io/delete-file f true))

(defn- scan-chunk!
  "One stretch of the video, from frames on the disk to hits with a time on
   them. The frames are gone again by the time this answers."
  [video start dur fps terms on-frame]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                       "recorda-ocr"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (extract-chunk! video dir start dur fps)
      (let [files (->> (.listFiles dir) (sort-by #(.getName ^File %)) vec)]
        (into []
              (mapcat
                (fn [{:keys [path words]}]
                  ;; The time comes off the filename rather than the position
                  ;; in the list. ffmpeg numbers what it wrote, and trusting
                  ;; that is what keeps a box on the right second even if a
                  ;; frame in the middle failed to be written at all.
                  (let [k (parse-long (re-find #"\d+" (.getName (io/file ^String path))))
                        t (+ start (/ (double (dec (long k))) fps))]
                    (map #(assoc % :t t) (frame-hits words terms)))))
              (ocr/read-frames files {:on-frame on-frame})))
      (finally (rm-tree! dir)))))

(defn hits-file ^File [id] (store/file id "redact.edn"))

(defn read-hits
  "The boxes the last scan found, or nil.

   Kept beside the take rather than in meta.edn, the same way `peaks.json` is:
   meta.edn holds the decisions a person made and this holds what a machine
   computed from them, and the second is always throwable-away."
  [id]
  (let [f (hits-file id)]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))

(defn settings
  "The redaction as the project has it, with the defaults filled in."
  [meta]
  (merge defaults (:redact meta)))

(defn scan-key
  "What a scan is only valid for.

   The terms, obviously. The sample rate, because a denser scan finds more. And
   `:rev`, which counts assemblies — trim the video or record into the middle
   of it and every time in the box list means a different moment. Comparing
   this is what makes a stale scan visible instead of quietly wrong."
  [meta]
  (let [s (settings meta)]
    {:terms (vec (sort (remove str/blank? (:terms s))))
     :fps   (double (:fps s))
     :rev   (or (:rev meta) 0)}))

(defn current?
  "Whether the scan on disk still describes this project."
  [id]
  (let [m (store/read-meta id)
        h (read-hits id)]
    (boolean (and h (= (:key h) (scan-key m))))))

(defn scan!
  "Look through the take for its terms and write down where they were.

   Runs to completion; the caller is expected to be a future and the page is
   expected to be polling `progress`. Roughly half of real time at two frames a
   second — a ten minute take is about three minutes of looking."
  [id]
  (let [m     (store/read-meta id)
        s     (settings m)
        terms (vec (remove str/blank? (map str/trim (:terms s))))
        video (store/file id "video.mp4")
        fps   (double (:fps s))]
    (cond
      (nil? m)              {:ok? false :error "no such project"}
      (not (.exists video)) {:ok? false :error "this project has no video"}
      (empty? terms)        (do (io/delete-file (hits-file id) true)
                                {:ok? true :boxes 0})
      (not (ocr/available?))
      {:ok? false :error "the OCR helper needs macOS and the Xcode command line tools"}

      :else
      (let [dur   (or (ff/duration video) 0.0)
            vw    (or (some-> (ff/probe video "v:0" "stream=width") parse-long) 0)
            vh    (or (some-> (ff/probe video "v:0" "stream=height") parse-long) 0)
            total (max 1 (long (Math/ceil (* dur fps))))]
        (reset! progress {:state :scanning :id id :done 0 :total total :hits 0})
        (try
          (let [raw
                (loop [start 0.0 acc []]
                  (if (>= start dur)
                    acc
                    (let [found (scan-chunk! video start
                                             (min chunk-seconds (- dur start))
                                             fps terms
                                             #(swap! progress update :done inc))]
                      (swap! progress update :hits + (count found))
                      (recur (+ start chunk-seconds) (into acc found)))))
                boxes (->> (tracks raw {:fps fps})
                           (map #(finish % s vw vh))
                           (sort-by :t0)
                           vec)]
            (spit (hits-file id)
                  (with-out-str
                    (clojure.pprint/pprint {:key    (scan-key m)
                                            :width  vw
                                            :height vh
                                            :at     (str (java.time.Instant/now))
                                            :frames total
                                            :boxes  boxes})))
            (reset! progress {:state :done :id id :done total :total total
                              :hits (count raw) :boxes (count boxes)})
            (t/log! :info (str "recorda: scanned " id " — " (count raw) " hits in "
                               total " frames, " (count boxes) " boxes"))
            {:ok? true :boxes (count boxes) :hits (count raw)})
          (catch Exception e
            (reset! progress {:state :failed :id id :error (.getMessage e)})
            (t/log! :error (str "recorda: scan of " id " failed: " (.getMessage e)))
            {:ok? false :error (.getMessage e)}))))))

;; ---------------------------------------------------------------------------
;; What the export does with it

(def ^:private box-limit
  "More boxes than a filter graph should be asked to hold.

   Not a truncation — going over this refuses the export and says so. Dropping
   the boxes past a limit is the one behaviour this feature must never have:
   it would export a file that looks redacted and is not, and nothing about it
   would say so."
  300)

(defn- blur-radius
  "Destructive at the scale of the text, and legal for the plane.

   boxblur wants a radius no larger than half the smaller side, and the chroma
   plane in 4:2:0 is half the size again. Three passes rather than one, because
   a single box blur leaves the stroke pattern of a word legible at the radii
   small text allows and three is close enough to a gaussian that it does not."
  [{:keys [w h]}]
  (max 2 (min 40 (quot (min (long w) (long h)) 3))))

(defn- interval [{:keys [t0 t1]}]
  (format "enable='between(t,%.3f,%.3f)'" (double t0) (double t1)))

(defn video-filter
  "The video half of the export's filter graph, ending in `[vout]`, or nil.

   Redaction first and the crop after it, so the boxes stay in the coordinates
   they were found in. Draw them against the crop instead and every box would
   have to be recomputed the moment the crop moved — the same reason the crop
   itself is a number over the footage rather than something baked into it."
  [boxes {:keys [style]} crop]
  (let [n (count boxes)]
    (when (or (pos? n) crop)
      (let [crop-str (when crop
                       (format "crop=%d:%d:%d:%d" (:w crop) (:h crop) (:x crop) (:y crop)))]
        (cond
          (zero? n)
          (str "[0:v]" crop-str "[vout]")

          ;; A solid box needs no second copy of the picture: drawbox paints
          ;; straight onto it, so this is one chain and costs almost nothing.
          (= :box (keyword style))
          (str "[0:v]"
               (str/join ","
                         (for [b boxes]
                           (format "drawbox=x=%d:y=%d:w=%d:h=%d:color=black@1.0:t=fill:%s"
                                   (:x b) (:y b) (:w b) (:h b) (interval b))))
               (when crop-str (str "," crop-str))
               "[vout]")

          :else
          (let [labels (map #(str "r" %) (range n))
                last-o (str "o" (dec n))]
            (str "[0:v]split=" (inc n) "[vbg]"
                 (str/join "" (map #(str "[" % "]") labels)) ";"
                 ;; Each region is cropped out, blurred on its own, and put
                 ;; back. Blurring the whole frame once and cutting pieces out
                 ;; of it would be the other way round, and would spend a
                 ;; 2560x1440 blur per frame to hide a line of text.
                 (str/join ""
                           (map-indexed
                             (fn [i b]
                               (let [r (blur-radius b)]
                                 (format "[r%d]crop=%d:%d:%d:%d,boxblur=%d:3:%d:3[b%d];"
                                         i (:w b) (:h b) (:x b) (:y b)
                                         r (max 1 (quot r 2)) i)))
                             boxes))
                 (str/join ""
                           (map-indexed
                             (fn [i b]
                               (format "[%s][b%d]overlay=%d:%d:%s[o%d];"
                                       (if (zero? i) "vbg" (str "o" (dec i)))
                                       i (:x b) (:y b) (interval b) i))
                             boxes))
                 (if crop-str
                   (str "[" last-o "]" crop-str "[vout]")
                   ;; Nothing after the last overlay, so it is the output. The
                   ;; trailing `;` from the loop above has to go with it.
                   (str "[" last-o "]null[vout]")))))))))

(defn for-export
  "What the export needs to know: the filter, and whether it may run at all.

   Refuses on a stale scan rather than using it. A scan is answers about
   frames, and the moment the terms or the assembly change those answers are
   about a video that no longer exists — exporting from them would produce a
   file that is blurred in the wrong places and looks exactly like one that is
   not."
  [id meta crop]
  (let [s     (settings meta)
        terms (remove str/blank? (:terms s))
        hits  (read-hits id)]
    (cond
      (empty? terms)
      {:ok? true :filter (video-filter [] s crop)}

      (nil? hits)
      {:ok? false :error "terms are set but nothing has been scanned yet — press Scan"}

      (not= (:key hits) (scan-key meta))
      {:ok? false :error "the scan is out of date — the terms or the video changed since. Scan again."}

      (> (count (:boxes hits)) box-limit)
      {:ok? false :error (str "the scan found " (count (:boxes hits))
                              " separate places, which is more than one export can hold. "
                              "Use fewer or more specific terms.")}

      :else
      {:ok? true :filter (video-filter (:boxes hits) s crop) :boxes (count (:boxes hits))})))
