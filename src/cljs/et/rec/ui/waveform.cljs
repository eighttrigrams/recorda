(ns et.rec.ui.waveform
  "The audio lane, drawn on a canvas.

   Reagent renders the element; everything inside it is drawn by hand, because
   8000 peaks are 8000 DOM nodes if you let hiccup do it and one pass of
   fillRect if you do not."
  (:require [reagent.core :as r]))

(defn- css-var [el name]
  (-> (js/getComputedStyle el) (.getPropertyValue name) (.trim)))

(defn- draw! [canvas peaks]
  (when (and canvas (seq peaks))
    (let [dpr  (or (.-devicePixelRatio js/window) 1)
          rect (.getBoundingClientRect canvas)
          w    (.-width rect)
          h    (.-height rect)
          n    (count peaks)]
      (when (and (pos? w) (pos? h))
        ;; The backing store is sized in device pixels and the context scaled to
        ;; match, or the waveform is soft on every retina display.
        (set! (.-width canvas) (js/Math.round (* w dpr)))
        (set! (.-height canvas) (js/Math.round (* h dpr)))
        (let [ctx (.getContext canvas "2d")
              mid (/ h 2)]
          (.setTransform ctx dpr 0 0 dpr 0 0)
          (.clearRect ctx 0 0 w h)
          (set! (.-fillStyle ctx) (css-var canvas "--accent"))
          (dotimes [x (js/Math.floor w)]
            ;; Each column takes the loudest peak falling under it, so a
            ;; narrower window hides no transient — it just stacks more of
            ;; them into the same pixel. Averaging here would make a clipped
            ;; passage and a quiet one look alike, which is the one thing
            ;; this lane is for.
            (let [from (js/Math.floor (* (/ x w) n))
                  to   (min n (max (inc from) (js/Math.floor (* (/ (inc x) w) n))))
                  amp  (loop [i from m 0]
                         (if (>= i to) m (recur (inc i) (max m (nth peaks i 0)))))
                  bar  (max 0.5 (* amp (- mid 2)))]
              (.fillRect ctx x (- mid bar) 1 (* 2 bar)))))))))

(defn waveform
  "peaks is the vector out of peaks.json.

   The canvas is captured through a :ref rather than reagent.core/dom-node,
   which reagent 2 no longer has."
  [_peaks]
  (let [node   (atom nil)
        latest (atom nil)
        redraw #(draw! @node @latest)]
    (r/create-class
      {:display-name "waveform"
       :component-did-mount
       (fn [_] (.addEventListener js/window "resize" redraw) (redraw))
       :component-will-unmount
       (fn [_] (.removeEventListener js/window "resize" redraw))
       :component-did-update (fn [_] (redraw))
       :reagent-render
       (fn [peaks]
         (reset! latest peaks)
         [:canvas {:ref (fn [el]
                          (reset! node el)
                          (when el (redraw)))}])})))
