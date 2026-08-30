(ns et.rec.ui.mic
  "The microphone, recorded in the browser.

   **This exists because ffmpeg cannot be trusted with it on this machine.**
   Its AVFoundation audio input drops roughly a 512-sample buffer ten times a
   second and concatenates what survives, so speech comes out with pieces cut
   from it. Measured against a click train played at exactly 100.000 ms, ffmpeg
   returned 88.5 ms; the browser, over a five minute capture, lost 91 ms in
   total — 0.03%.

   An **AudioWorklet**, not a ScriptProcessorNode. The latter is deprecated
   precisely because it runs on the main thread and drops audio whenever the
   page is busy, which is the failure we are here to avoid, and a screen
   recorder's page is not idle."
  (:require [reagent.core :as r]))

(defonce state
  (r/atom {:recording? false :monitoring? false :device nil
           :samples 0 :error nil
           :peak 0.0        ;; loudest sample in the last moment, 0..1
           :hold 0.0}))     ;; the loudest since the meter was last reset

(defonce ^:private cap (atom nil))

;; The meter is updated from the worklet's messages, which arrive 375 times a
;; second. Writing a reagent atom that often would re-render the page at the
;; same rate for a bar that only has to look alive, so the peak is accumulated
;; here and published on an interval.
(defonce ^:private pending (atom 0.0))

(defn- note-peak! [^js chunk]
  (let [n (.-length chunk)]
    (loop [i 0 m 0.0]
      (if (>= i n)
        (when (> m @pending) (reset! pending m))
        (recur (inc i) (max m (js/Math.abs (aget chunk i))))))))

(defonce ^:private _meter
  (js/setInterval
    (fn []
      (when (or (:recording? @state) (:monitoring? @state))
        (let [p @pending]
          (reset! pending 0.0)
          (swap! state (fn [s] (assoc s :peak p :hold (max (:hold s) p)))))))
    60))

(defn reset-hold! [] (swap! state assoc :hold 0.0))

(defn dbfs
  "0..1 as dBFS, floored so a silent input does not read as minus infinity."
  [x]
  (if (or (nil? x) (<= x 0.0000001)) -80.0 (* 20 (js/Math.log10 x))))

(def ^:private worklet-src
  "Ships as a blob rather than a file, because it is nine lines and a served
   asset is one more thing to keep in step with this namespace. It forwards
   each 128-sample render quantum untouched; all the accumulating happens on
   the main thread, where a late message costs nothing."
  "class Rec extends AudioWorkletProcessor {
     process (inputs) {
       const ch = inputs[0][0];
       if (ch) this.port.postMessage(new Float32Array(ch));
       return true;
     }
   }
   registerProcessor('rec', Rec);")

(def ^:private never-pick
  "Inputs that must never be chosen by accident.

   A Continuity device is a *phone*. Opening it does not merely record the wrong
   thing — it takes over the handset, interrupting whatever the person holding
   it was doing. That is a real-world side effect from a fallback branch, so the
   fallback does not get to reach it."
  #"iphone|ipad|continuity")

(defn- inputs [devices]
  (filter #(= "audioinput" (.-kind %)) devices))

(defn- pick-device
  "The input whose label contains `wanted`; failing that the system default;
   and never a phone."
  [devices wanted]
  (let [ins (remove #(re-find never-pick (.toLowerCase (or (.-label %) ""))) (inputs devices))
        w   (some-> wanted .toLowerCase)]
    (or (when w
          (first (filter #(.includes (.toLowerCase (or (.-label %) "")) w) ins)))
        (first (filter #(= "default" (.-deviceId %)) ins))
        (first ins))))

(defn- have-labels?
  "Whether permission has already been granted. Device labels are blank until
   it has, which is the only reason this namespace ever opens a stream it does
   not want."
  [devices]
  (boolean (some #(seq (or (.-label %) "")) (inputs devices))))

(defn- with-devices
  "Call `f` with the device list, asking for permission only if we do not
   already have it.

   The warm-up `getUserMedia({audio: true})` takes the **system default**, which
   on this machine is a phone — so doing it on every recording, as this used to,
   interrupted the handset every time. Chrome remembers the grant per origin, so
   after the first time the labels are simply there and no stream is opened."
  [f on-error]
  (-> (.enumerateDevices (.-mediaDevices js/navigator))
      (.then (fn [devs]
               (if (have-labels? (array-seq devs))
                 devs
                 (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
                     (.then (fn [warm]
                              (.forEach (.getTracks warm) (fn [t] (.stop t)))
                              (.enumerateDevices (.-mediaDevices js/navigator))))))))
      (.then f)
      (.catch (fn [e] (when on-error (on-error (str e)))))))

(defn list-devices
  "Every audio input the browser can see."
  []
  (js/Promise.
    (fn [resolve _reject]
      (with-devices
        (fn [devs]
          (resolve (mapv (fn [d] {:id (.-deviceId d) :label (.-label d)})
                         (inputs (array-seq devs)))))
        (fn [_] (resolve []))))))

(defn start!
  "Open the microphone and begin recording.

   `on-live` is called with the epoch millisecond at which the **first audio
   actually arrived** — not when the stream was requested. The two are not the
   same instant: an interface has a clock of its own to start, and on this
   machine that has been over a second. Everything that follows lines up against
   the moment sound really began, so the caller should not start the picture
   until this fires."
  [preferred-name on-live on-error]
  (with-devices
    (fn [devs]
      (let [d (pick-device (array-seq devs) preferred-name)]
        (-> (.getUserMedia (.-mediaDevices js/navigator)
                           #js {:audio (if d
                                         #js {:deviceId #js {:exact (.-deviceId d)}
                                              :echoCancellation false
                                              :noiseSuppression false
                                              :autoGainControl  false}
                                         #js {:echoCancellation false
                                              :noiseSuppression false
                                              :autoGainControl  false})})
            (.then (fn [stream]
                     (let [ctx (js/AudioContext. #js {:sampleRate 48000})
                           url (.createObjectURL js/URL (js/Blob. #js [worklet-src]
                                                                  #js {:type "application/javascript"}))]
                       (-> (.addModule (.-audioWorklet ctx) url)
                           (.then (fn []
                                    (let [src    (.createMediaStreamSource ctx stream)
                                          node   (js/AudioWorkletNode. ctx "rec")
                                          chunks (array)
                                          live   (atom nil)]
                                      (set! (.. node -port -onmessage)
                                            (fn [e]
                                              (when (nil? @live)
                                                (reset! live (js/Date.now))
                                                (on-live @live))
                                              (note-peak! (.-data e))
                                              (.push chunks (.-data e))))
                                      (.connect src node)
                                      (reset! cap {:ctx ctx :stream stream :node node
                                                   :chunks chunks :rate (.-sampleRate ctx)})
                                      (swap! state assoc :recording? true :monitoring? false
                                             :error nil :hold 0.0
                                             :device (some-> (aget (.getAudioTracks stream) 0) .-label)))))))))
            (.catch (fn [e]
                      (swap! state assoc :recording? false :error (str e))
                      (when on-error (on-error (str e))))))))
    (fn [e] (swap! state assoc :recording? false :error e)
            (when on-error (on-error e)))))

(defn- encode-wav
  "16-bit mono PCM. Big enough to matter — a minute is 5.7 MB — but this is
   loopback to a server on the same machine."
  [^js chunks rate]
  (let [total (areduce chunks i acc 0 (+ acc (.-length (aget chunks i))))
        pcm   (js/Float32Array. total)]
    (loop [i 0 off 0]
      (when (< i (.-length chunks))
        (.set pcm (aget chunks i) off)
        (recur (inc i) (+ off (.-length (aget chunks i))))))
    (let [buf (js/ArrayBuffer. (+ 44 (* total 2)))
          v   (js/DataView. buf)
          ws  (fn [off s] (dotimes [i (count s)] (.setUint8 v (+ off i) (.charCodeAt s i))))]
      (ws 0 "RIFF") (.setUint32 v 4 (+ 36 (* total 2)) true) (ws 8 "WAVEfmt ")
      (.setUint32 v 16 16 true) (.setUint16 v 20 1 true) (.setUint16 v 22 1 true)
      (.setUint32 v 24 rate true) (.setUint32 v 28 (* rate 2) true)
      (.setUint16 v 32 2 true) (.setUint16 v 34 16 true)
      (ws 36 "data") (.setUint32 v 40 (* total 2) true)
      (dotimes [i total]
        (let [s (max -1 (min 1 (aget pcm i)))]
          (.setInt16 v (+ 44 (* i 2)) (if (neg? s) (* s 0x8000) (* s 0x7FFF)) true)))
      {:buffer buf :samples total :seconds (/ total rate)})))

(defn stop!
  "Close the microphone and return {:buffer ArrayBuffer :samples n :seconds s}."
  []
  (when-let [{:keys [ctx stream node chunks rate]} @cap]
    (try (.disconnect node) (catch :default _ nil))
    (.forEach (.getTracks stream) (fn [t] (.stop t)))
    (let [wav (encode-wav chunks rate)]
      (.close ctx)
      (reset! cap nil)
      (swap! state assoc :recording? false :samples (:samples wav))
      wav)))

(defn cancel! []
  (when-let [{:keys [ctx stream node]} @cap]
    (try (.disconnect node) (catch :default _ nil))
    (.forEach (.getTracks stream) (fn [t] (.stop t)))
    (.close ctx)
    (reset! cap nil)
    (swap! state assoc :recording? false)))

(defn monitor!
  "Open the microphone without recording, so the level meter has something to
   show. This is what you set the interface's gain against — the alternative is
   recording a take, listening, adjusting and recording again."
  [preferred-name]
  (when-not (or (:recording? @state) (:monitoring? @state))
    (with-devices
      (fn [devs]
        (let [d (pick-device (array-seq devs) preferred-name)]
          (-> (.getUserMedia (.-mediaDevices js/navigator)
                             #js {:audio (if d
                                           #js {:deviceId #js {:exact (.-deviceId d)}
                                                :echoCancellation false
                                                :noiseSuppression false
                                                :autoGainControl  false}
                                           #js {:echoCancellation false
                                                :noiseSuppression false
                                                :autoGainControl  false})})
              (.then (fn [stream]
                       (let [ctx (js/AudioContext. #js {:sampleRate 48000})
                             url (.createObjectURL js/URL (js/Blob. #js [worklet-src]
                                                                    #js {:type "application/javascript"}))]
                         (-> (.addModule (.-audioWorklet ctx) url)
                             (.then (fn []
                                      (let [src  (.createMediaStreamSource ctx stream)
                                            node (js/AudioWorkletNode. ctx "rec")]
                                        (set! (.. node -port -onmessage)
                                              (fn [e] (note-peak! (.-data e))))
                                        (.connect src node)
                                        (reset! cap {:ctx ctx :stream stream :node node
                                                     :chunks (array) :rate (.-sampleRate ctx)})
                                        (swap! state assoc :monitoring? true :error nil :hold 0.0
                                               :device (some-> (aget (.getAudioTracks stream) 0) .-label)))))))))
              (.catch (fn [e] (swap! state assoc :monitoring? false :error (str e)))))))
      (fn [e] (swap! state assoc :monitoring? false :error e)))))

(defn stop-monitor! []
  (when (:monitoring? @state)
    (when-let [{:keys [ctx stream node]} @cap]
      (try (.disconnect node) (catch :default _ nil))
      (.forEach (.getTracks stream) (fn [t] (.stop t)))
      (.close ctx)
      (reset! cap nil))
    (swap! state assoc :monitoring? false :peak 0.0)))
