(ns et.rec.ui.state
  "One atom, every call. The same shape the sibling apps use."
  (:require [et.rec.ui.api :as api]
            [et.rec.ui.engine :as engine]
            [et.rec.ui.mic :as mic]
            [reagent.core :as r]))

(defonce app
  (r/atom {:devices    nil
           :status     {:status "idle"}
           :recordings []
           :selected   nil       ;; id of the take on the stage
           :peaks      nil       ;; {:duration n :peaks [...]} for that take
           :time       0.0
           :duration   0.0
           :playing?   false
           :error      nil}))

(defn recording? [] (= "recording" (get-in @app [:status :status])))
(defn processing? []
  (or (:uploading? @app)
      (#{"processing" "awaiting-audio"} (get-in @app [:status :status]))))
(defn busy? [] (or (recording?) (processing?)))

(defn selected-take []
  (let [id (:selected @app)]
    (first (filter #(= id (:id %)) (:recordings @app)))))

;; --- loading ---------------------------------------------------------------

(defn fetch-devices! []
  (api/GET "/api/devices" #(swap! app assoc :devices %)))

(defn fetch-recordings! []
  (api/GET "/api/recordings" #(swap! app assoc :recordings %)))

(defn fetch-peaks! [id]
  (swap! app assoc :peaks nil)
  (api/GET (str "/media/" id "/peaks.json")
           #(when (= id (:selected @app)) (swap! app assoc :peaks %))))

(defn select! [id]
  (swap! app assoc :selected id :time 0.0 :playing? false
         :duration (or (:duration (first (filter (fn [r] (= id (:id r)))
                                                 (:recordings @app)))) 0.0))
  ;; Decoding the whole take up front is what buys playback that cannot stall.
  (engine/load! id)
  (fetch-peaks! id))

;; --- the take --------------------------------------------------------------

(defn fetch-status!
  "Also refreshes the library on the edge where a take finishes, so the new
   row appears without the user reloading. Comparing against the previous
   status is what makes that one fetch instead of one per second."
  []
  (let [was (get-in @app [:status :status])]
    (api/GET "/api/status"
             (fn [s]
               (swap! app assoc :status s)
               (when (and (= was "processing") (= (:status s) "idle"))
                 (fetch-recordings!))))))

(defn- upload-audio!
  "Send the microphone recording for a take, with the lead time that lines it
   up against the picture. Sent as raw bytes rather than through cljs-ajax,
   which has no comfortable way to post an ArrayBuffer."
  [id buffer lead-ms]
  (swap! app assoc :uploading? true)
  (-> (js/fetch (str "/api/recordings/" id "/audio")
                #js {:method  "POST"
                     :headers #js {"Content-Type"    "application/octet-stream"
                                   "X-Audio-Lead-Ms" (str lead-ms)}
                     :body    buffer})
      (.then (fn [res]
               (swap! app assoc :uploading? false)
               (when-not (.-ok res)
                 (swap! app assoc :error "the server would not take the audio"))
               (fetch-recordings!)
               (fetch-status!)))
      (.catch (fn [e]
                (swap! app assoc :uploading? false
                       :error (str "could not send the audio: " e))
                (fetch-status!)))))

(defn start!
  "Begin a take.

   **The microphone leads.** It is opened first and the screen capture is not
   started until real audio has arrived, because an interface takes its own
   time to come up — over a second, on this machine — and a picture that starts
   during that window is a picture with no sound under its opening seconds.

   The server answers with `video-started-at`, the instant frames actually
   began, and the difference between that and the moment audio went live is the
   lead that gets trimmed off the front of the recording when it is uploaded."
  []
  (swap! app assoc :error nil :audio-live-ms nil)
  ;; A monitor holds the same device; take it back before recording.
  (mic/stop-monitor!)
  (mic/start!
    (get-in @app [:devices :chosen-mic :name])
    ;; on-live: sound is really flowing, so now start the picture
    (fn [live-ms]
      (swap! app assoc :audio-live-ms live-ms)
      (api/POST "/api/record/start"
                (fn [st] (swap! app assoc :status st))
                (fn [e]
                  (mic/cancel!)
                  (swap! app assoc :error (or (get-in e [:response :error])
                                              "could not start the screen capture")))))
    (fn [e] (swap! app assoc :error (str "microphone: " e)))))

(defn stop!
  "End the take: stop the picture, then hand over the sound."
  []
  (api/POST "/api/record/stop"
            (fn [res]
              (let [id   (:id res)
                    wav  (mic/stop!)
                    lead (max 0 (- (or (get-in @app [:status :video-started-at]) 0)
                                   (or (:audio-live-ms @app) 0)))]
                (fetch-status!)
                (if (and id wav)
                  (upload-audio! id (:buffer wav) lead)
                  (swap! app assoc :error "no audio was recorded for this take"))))
            (fn [e]
              (mic/cancel!)
              (swap! app assoc :error (or (get-in e [:response :error]) "could not stop")))))

(defn delete! [id]
  (api/DELETE (str "/api/recordings/" id)
              (fn [_]
                (when (= id (:selected @app))
                  (engine/unload!)
                  (swap! app assoc :selected nil :peaks nil))
                (fetch-recordings!))))

(defn rename! [id title]
  (api/PUT (str "/api/recordings/" id) {:title title}
           (fn [_] (fetch-recordings!))))

(defn refresh-devices! []
  (api/POST "/api/devices/refresh" #(swap! app assoc :devices %)))

;; --- polling ---------------------------------------------------------------

(defonce ^:private poller (atom nil))

(defn start-polling! []
  (when-not @poller
    (reset! poller (js/setInterval fetch-status! 1000))))
