(ns et.rec.ui.waveform
  "The audio lane, drawn on a canvas.

   Reagent renders the element; everything inside it is drawn by hand, because
   8000 peaks are 8000 DOM nodes if you let hiccup do it and one pass of
   fillRect if you do not."
  (:require [reagent.core :as r]))

(def ^:private aim-dbfs
  "The level the record meter tells you to aim at, drawn on the lane as well so
   the same number governs before the take and after it. Changing it here and
   in recorder/meter is one thought, not two."
  -12.0)

(def ^:private floor-dbfs
  "The bottom of the lane. The same -60 the record meter uses, because below it
   there is nothing left to judge — and spending three quarters of the lane on
   it was what left the -12 mark sitting in the middle of an empty box."
  -60.0)

(defn- lane-frac
  "An amplitude as a fraction of the lane's half-height, on a dB scale.

   **Not linear.** -12 dBFS is a quarter of full scale as a number, so on a
   linear axis the level you are told to aim at sits a quarter of the way up
   and ordinary speech at -25 dB is a thread either side of the centre line —
   a lane mostly made of empty space, with the one mark that matters buried in
   the middle of it. On the meter's -60..0 scale that same -12 lands at 80%,
   near the edge where an aim mark belongs, and a quiet take is legible.

   It is the record meter's own mapping, so a level read before the take and
   the same level read after it are read off the same ruler."
  [a]
  (if (<= a 0.0)
    0.0
    (let [db (* 20 (js/Math.log10 a))]
      ;; (db - floor) / (0 - floor), which is this with one operation.
      (max 0.0 (min 1.0 (- 1.0 (/ db floor-dbfs)))))))

(def ^:private aim-frac
  "Where the -12 mark falls on that scale: 0.8 of the half-height."
  (- 1.0 (/ aim-dbfs floor-dbfs)))

(defn- css-var [el name]
  (-> (js/getComputedStyle el) (.getPropertyValue name) (.trim)))

(defn- draw!
  "Draw the peaks at `gain`.

   The lane is an absolute scale — a peak of 1.0 is the top edge, nothing is
   normalised — so scaling by the lane's gain is not decoration: it is what
   will actually be in the file. Export applies `volume` and then mixes with
   `normalize=0`, so a slider past unity really can push the voice over 0 dBFS,
   and the only place that used to be visible was the exported audio."
  [canvas peaks gain]
  (when (and canvas (seq peaks))
    (let [dpr  (or (.-devicePixelRatio js/window) 1)
          rect (.getBoundingClientRect canvas)
          w    (.-width rect)
          h    (.-height rect)
          n    (count peaks)
          g    (double (or gain 1.0))]
      (when (and (pos? w) (pos? h))
        ;; The backing store is sized in device pixels and the context scaled to
        ;; match, or the waveform is soft on every retina display.
        (set! (.-width canvas) (js/Math.round (* w dpr)))
        (set! (.-height canvas) (js/Math.round (* h dpr)))
        (let [ctx    (.getContext canvas "2d")
              mid    (/ h 2)
              full   (- mid 2)          ; pixels from the middle to 0 dBFS
              accent (css-var canvas "--accent")
              over   (css-var canvas "--record")
              guide  (css-var canvas "--muted-text")]
          (.setTransform ctx dpr 0 0 dpr 0 0)
          (.clearRect ctx 0 0 w h)
          (set! (.-fillStyle ctx) accent)
          ;; One fillStyle per colour change rather than per column: at a
          ;; thousand columns a redraw the parse cost shows up while the slider
          ;; is under the pointer.
          (loop [x 0, red? false]
            (when (< x (js/Math.floor w))
              ;; Each column takes the loudest peak falling under it, so a
              ;; narrower window hides no transient — it just stacks more of
              ;; them into the same pixel. Averaging here would make a clipped
              ;; passage and a quiet one look alike, which is the one thing
              ;; this lane is for.
              (let [from (js/Math.floor (* (/ x w) n))
                    to   (min n (max (inc from) (js/Math.floor (* (/ (inc x) w) n))))
                    amp  (loop [i from m 0]
                           (if (>= i to) m (recur (inc i) (max m (nth peaks i 0)))))
                    a    (* amp g)
                    ;; Over full scale there is no more lane to draw into, so
                    ;; the column is clamped and coloured instead. Red here
                    ;; means "this is what clips", not "this is loud". Tested
                    ;; on the amplitude, not on the drawn height, because the
                    ;; dB scale reaches the top edge a hair before 1.0.
                    hot? (> a 1.0)
                    bar  (max 0.5 (* (lane-frac a) full))]
                (when (not= hot? red?)
                  (set! (.-fillStyle ctx) (if hot? over accent)))
                (.fillRect ctx x (- mid bar) 1 (* 2 bar))
                (recur (inc x) hot?))))
          ;; The aim line goes on top of the wave, not under it: under it, the
          ;; one passage where you want to check the level is the one passage
          ;; loud enough to hide the line.
          (let [y (* aim-frac full)]
            (set! (.-strokeStyle ctx) guide)
            (set! (.-lineWidth ctx) 1)
            (.setLineDash ctx #js [3 3])
            (.beginPath ctx)
            (doseq [yy [(- mid y) (+ mid y)]]
              ;; Half-pixel offset, or a 1px line lands on a boundary and is
              ;; drawn as two grey ones.
              (.moveTo ctx 0 (+ 0.5 (js/Math.floor yy)))
              (.lineTo ctx w (+ 0.5 (js/Math.floor yy))))
            (.stroke ctx)
            (.setLineDash ctx #js [])
            (set! (.-font ctx) "9px system-ui, sans-serif")
            (set! (.-textAlign ctx) "right")
            ;; Below the line, not above it: at 80% of the half-height there
            ;; is no longer room above for nine pixels of text.
            (let [label "-12 dB"
                  tw    (.-width (.measureText ctx label))
                  base  (+ (- mid y) 11)]
              ;; A loud passage reaches this corner, and grey text on the wave
              ;; is unreadable exactly when the lane is busiest. Clearing the
              ;; box lets the lane's own background back through, which needs
              ;; no colour of its own to stay right in either theme.
              (.clearRect ctx (- w 7 tw) (- base 9) (+ tw 6) 12)
              (set! (.-fillStyle ctx) guide)
              (.fillText ctx label (- w 4) base))))))))

(defn waveform
  "peaks is the vector out of peaks.json, gain the voice lane's level.

   The canvas is captured through a :ref rather than reagent.core/dom-node,
   which reagent 2 no longer has."
  [_peaks _gain]
  (let [node   (atom nil)
        latest (atom [nil 1.0])
        redraw #(let [[p g] @latest] (draw! @node p g))]
    (r/create-class
      {:display-name "waveform"
       :component-did-mount
       (fn [_] (.addEventListener js/window "resize" redraw) (redraw))
       :component-will-unmount
       (fn [_] (.removeEventListener js/window "resize" redraw))
       :component-did-update (fn [_] (redraw))
       :reagent-render
       (fn [peaks gain]
         (reset! latest [peaks gain])
         [:canvas {:ref (fn [el]
                          (reset! node el)
                          (when el (redraw)))}])})))
