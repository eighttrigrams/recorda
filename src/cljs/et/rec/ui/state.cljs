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
           ;; Where the next sitting lands. Deliberately **not sticky**: it goes
           ;; back to :append after every take, because a mode that quietly
           ;; survives is a mode that eats the next recording's worth of
           ;; material while you think you are adding to the end.
           :record-mode :append
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
  (api/GET (str "/media/" id "/peaks.json?v=" (js/Date.now))
           #(when (= id (:selected @app)) (swap! app assoc :peaks %))))

(defn version
  "What every cached thing about a project is keyed on.

   `:rev` counts assemblies. The duration used to serve as the version on its
   own, and mostly did — but replacing from the playhead can land on the same
   length it started at, and then the tab goes on playing the file it already
   had. Counting rebuilds cannot collide that way."
  [take]
  (str (or (:rev take) 0) "-" (or (:duration take) 0)))

(defn select! [id]
  (let [take (first (filter (fn [r] (= id (:id r))) (:recordings @app)))]
    (swap! app assoc :selected id :time 0.0 :playing? false
           :duration (or (:duration take) 0.0))
    ;; Decoding the whole thing up front is what buys playback that cannot
    ;; stall.
    (engine/load! id (version take))
    (fetch-peaks! id)))

;; --- the take --------------------------------------------------------------

(defn fetch-status!
  "Also refreshes the library on the edge where a take finishes, so the new
   row appears without the user reloading. Comparing against the previous
   status is what makes that one fetch instead of one per second.

   `:stuck-polls` counts consecutive polls that found the server waiting for
   audio this browser is not sending. Since this browser is the only thing that
   ever uploads any, a run of those means the take can no longer be finished —
   and a few seconds of it, rather than one poll, so that the ordinary gap
   between stopping and the upload starting never shows an alarm."
  []
  (let [was (get-in @app [:status :status])]
    (api/GET "/api/status"
             (fn [s]
               (swap! app
                      (fn [a]
                        (-> a
                            (assoc :status s)
                            (update :stuck-polls
                                    #(if (and (= "awaiting-audio" (:status s))
                                              (not (:uploading? a)))
                                       (inc (or % 0))
                                       0)))))
               (when (and (= was "processing") (= (:status s) "idle"))
                 (fetch-recordings!))))))

(defn stuck?
  "The server is holding a take open for audio that is not coming."
  []
  (> (or (:stuck-polls @app) 0) 3))

(defn- upload-audio!
  "Send the microphone recording for a take, with the lead time that lines it
   up against the picture. Sent as raw bytes rather than through cljs-ajax,
   which has no comfortable way to post an ArrayBuffer."
  [id n buffer lead-ms]
  (swap! app assoc :uploading? true)
  (-> (js/fetch (str "/api/recordings/" id "/segments/" n "/audio")
                #js {:method  "POST"
                     :headers #js {"Content-Type"    "application/octet-stream"
                                   "X-Audio-Lead-Ms" (str lead-ms)}
                     :body    buffer})
      (.then (fn [res]
               ;; Back to appending. See :record-mode above.
               (swap! app assoc :uploading? false :record-mode :append :record-at nil)
               (when-not (.-ok res)
                 (swap! app assoc :error "the server would not take the audio"))
               (fetch-recordings!)
               (fetch-status!)
               (select! (:selected @app))))
      (.catch (fn [e]
                (swap! app assoc :uploading? false :record-mode :append :record-at nil
                       :error (str "could not send the audio: " e))
                ;; Nothing else will ever finish this take, so say so rather
                ;; than leaving the server waiting on a browser that has given
                ;; up.
                (api/POST "/api/record/abandon" (fn [_] (fetch-status!)) (fn [_] (fetch-status!)))))))

(defn create-project!
  "Make a new, empty project and open it. A project is one video; recording
   into it is what fills it."
  []
  (api/POST "/api/recordings"
            (fn [p]
              (fetch-recordings!)
              (swap! app assoc :selected (:id p) :time 0.0 :duration 0.0 :peaks nil))
            #(swap! app assoc :error "could not create the project")))

(defn trim!
  "Cut the open project's tail at the playhead."
  [id at]
  (swap! app assoc :error nil)
  (api/POST (str "/api/recordings/" id "/trim?at=" at)
            (fn [_] (fetch-recordings!) (select! id))
            #(swap! app assoc :error (or (get-in % [:response :error]) "could not trim"))))

(defn untrim!
  "Clear every edit: back to plain appended sittings, in the order they were
   recorded. A sitting recorded into the middle goes back to being the last one
   — it is not lost, because nothing ever moved but the arrangement."
  [id]
  (api/POST (str "/api/recordings/" id "/untrim")
            (fn [_] (fetch-recordings!) (select! id))
            #(swap! app assoc :error (or (get-in % [:response :error]) "could not undo the trim"))))

(defn start!
  "Record another sitting onto the open project.

   **The microphone leads.** It is opened first and the screen capture is not
   started until real audio has arrived, because an interface takes its own
   time to come up — over a second, on this machine — and a picture that starts
   during that window is a picture with no sound under its opening seconds.

   The server answers with `video-started-at`, the instant frames actually
   began, and the difference between that and the moment audio went live is the
   lead that gets trimmed off the front of the recording when it is uploaded.

   **Where it lands is decided now and applied later.** The mode and the
   playhead go with the request, the server writes them down, and the
   arrangement only moves once the sitting has produced something — so a take
   that fails leaves the project as it was. Recording at the playhead answers
   with the position it will really cut at, which is the nearest keyframe and
   not necessarily the one asked for."
  [project-id]
  (let [mode (or (:record-mode @app) :append)
        at   (if (= :append mode) 0.0 (:time @app))]
    (swap! app assoc :error nil :audio-live-ms nil :record-at nil)
    ;; A monitor holds the same device; take it back before recording.
    (mic/stop-monitor!)
    (mic/start!
      (get-in @app [:devices :chosen-mic :name])
      ;; on-live: sound is really flowing, so now start the picture
      (fn [live-ms]
        (swap! app assoc :audio-live-ms live-ms)
        (api/POST (str "/api/recordings/" project-id "/record/start"
                       "?mode=" (name mode) "&at=" at)
                  (fn [st]
                    (swap! app assoc :status st)
                    (when-not (= :append mode)
                      (swap! app assoc :record-at (:at st))))
                  (fn [e]
                    (mic/cancel!)
                    (swap! app assoc :error (or (get-in e [:response :error])
                                                "could not start the screen capture")))))
      (fn [e] (swap! app assoc :error (str "microphone: " e))))))

(defn stop!
  "End the take: stop the picture, then hand over the sound."
  []
  (api/POST "/api/record/stop"
            (fn [res]
              (let [id   (:id res)
                    n    (:segment res)
                    wav  (mic/stop!)
                    lead (max 0 (- (or (get-in @app [:status :video-started-at]) 0)
                                   (or (:audio-live-ms @app) 0)))]
                (fetch-status!)
                (if (and id n wav)
                  (upload-audio! id n (:buffer wav) lead)
                  (swap! app assoc :error "no audio was recorded for this sitting"))))
            (fn [e]
              (mic/cancel!)
              (swap! app assoc :error (or (get-in e [:response :error]) "could not stop")))))

(defn abandon!
  "Give up on a sitting the browser can no longer finish.

   The picture is captured on the server and the sound here, so a failed upload
   leaves a take only this side knows is never going to complete. Saying so is
   what lets the app record again — without it the server waits for audio
   forever and only a restart clears it."
  []
  (api/POST "/api/record/abandon"
            (fn [_] (swap! app assoc :uploading? false :record-mode :append :record-at nil)
                    (fetch-status!) (fetch-recordings!))
            (fn [_] (swap! app assoc :error "could not give up on that take"))))

(defn delete-clip!
  "Drop one piece from the open project's arrangement.

   Nothing leaves the disk — the sitting behind the piece stays whole, so this
   is undoable with `undo edits`."
  [id i]
  (swap! app assoc :error nil)
  (api/DELETE (str "/api/recordings/" id "/clips/" i)
              (fn [_] (fetch-recordings!) (select! id))
              #(swap! app assoc :error (or (get-in % [:response :error])
                                           "could not delete that piece"))))

(defn split-at!
  "Put a marker at `at` seconds on the open project.

   Changes nothing about what plays — it only cuts one piece into two, so that
   there is something to take hold of."
  [id at]
  (swap! app assoc :error nil)
  (api/POST (str "/api/recordings/" id "/split?at=" at)
            (fn [_] (fetch-recordings!) (select! id))
            #(swap! app assoc :error (or (get-in % [:response :error])
                                         "could not put a marker there"))))

(defn delete-seam!
  "Remove a marker, so the two pieces either side of it become one again."
  [id i]
  (swap! app assoc :error nil)
  (api/DELETE (str "/api/recordings/" id "/seams/" i)
              (fn [_] (fetch-recordings!) (select! id))
              #(swap! app assoc :error (or (get-in % [:response :error])
                                           "could not remove that marker"))))

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

(defn set-crop!
  "The area of the video to keep, in the video's own pixels. Applied on export,
   so this can be redrawn at any time and costs nothing until you export."
  [id c]
  (api/PUT (str "/api/recordings/" id "/crop") c
           (fn [_] (fetch-recordings!))
           #(swap! app assoc :error (or (get-in % [:response :error]) "could not set the area"))))

(defn clear-crop! [id]
  (api/PUT (str "/api/recordings/" id "/crop") {}
           (fn [_] (fetch-recordings!))
           #(swap! app assoc :error "could not clear the area")))

(defn export!
  "Mux a take into one mp4 and hand it straight to the browser's downloads.

   The download is triggered rather than offered as a link, because the file is
   rebuilt on every call: a link would be a URL whose contents change under it,
   and the moment edits exist that is a genuinely confusing thing to have kept
   in a tab."
  [id]
  (swap! app assoc :exporting? true :error nil)
  (api/POST (str "/api/recordings/" id "/export")
            (fn [r]
              (swap! app assoc :exporting? false)
              (let [title (or (:title (first (filter #(= id (:id %)) (:recordings @app)))) id)
                    a     (.createElement js/document "a")]
                (set! (.-href a) (:url r))
                (set! (.-download a) (str title ".mp4"))
                (.appendChild (.-body js/document) a)
                (.click a)
                (.remove a)))
            (fn [e]
              (swap! app assoc :exporting? false
                     :error (or (get-in e [:response :error]) "export failed")))))

(defn refresh-devices! []
  (api/POST "/api/devices/refresh" #(swap! app assoc :devices %)))

;; --- polling ---------------------------------------------------------------

(defonce ^:private poller (atom nil))

(defn start-polling! []
  (when-not @poller
    (reset! poller (js/setInterval fetch-status! 1000))))
