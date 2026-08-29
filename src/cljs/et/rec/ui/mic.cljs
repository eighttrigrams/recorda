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
  (r/atom {:recording? false :device nil :samples 0 :error nil}))

(defonce ^:private cap (atom nil))

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

(defn- pick-device
  "The input whose label contains `wanted`, else the system default."
  [devices wanted]
  (let [w (some-> wanted .toLowerCase)]
    (or (when w
          (first (filter #(and (= "audioinput" (.-kind %))
                               (.includes (.toLowerCase (.-label %)) w))
                         devices)))
        (first (filter #(= "audioinput" (.-kind %)) devices)))))

(defn list-devices
  "Every audio input the browser can see. Labels are only populated once
   permission has been given, which is why this asks for a stream first and
   immediately drops it."
  []
  (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
      (.then (fn [s]
               (.forEach (.getTracks s) (fn [t] (.stop t)))
               (.enumerateDevices (.-mediaDevices js/navigator))))
      (.then (fn [devs]
               (->> (array-seq devs)
                    (filter #(= "audioinput" (.-kind %)))
                    (mapv (fn [d] {:id (.-deviceId d) :label (.-label d)})))))))

(defn start!
  "Open the microphone and begin recording.

   `on-live` is called with the epoch millisecond at which the **first audio
   actually arrived** — not when the stream was requested. The two are not the
   same instant: an interface has a clock of its own to start, and on this
   machine that has been over a second. Everything that follows lines up
   against the moment sound really began, so the caller should not start the
   picture until this fires."
  [preferred-name on-live on-error]
  (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
      (.then (fn [warm]
               (.forEach (.getTracks warm) (fn [t] (.stop t)))
               (.enumerateDevices (.-mediaDevices js/navigator))))
      (.then (fn [devs]
               (let [d  (pick-device (array-seq devs) preferred-name)
                     cs (if d
                          #js {:deviceId #js {:exact (.-deviceId d)}
                               :echoCancellation false
                               :noiseSuppression false
                               :autoGainControl  false}
                          #js {:echoCancellation false
                               :noiseSuppression false
                               :autoGainControl  false})]
                 (.getUserMedia (.-mediaDevices js/navigator) #js {:audio cs}))))
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
                                        (.push chunks (.-data e))))
                                (.connect src node)
                                (reset! cap {:ctx ctx :stream stream :node node
                                             :chunks chunks :rate (.-sampleRate ctx)})
                                (swap! state assoc :recording? true :error nil
                                       :device (some-> (aget (.getAudioTracks stream) 0) .-label)))))))))
      (.catch (fn [e]
                (swap! state assoc :recording? false :error (str e))
                (when on-error (on-error (str e)))))))

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
