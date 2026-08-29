(ns et.rec.ui.state
  "One atom, every call. The same shape the sibling apps use."
  (:require [et.rec.ui.api :as api]
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
(defn processing? [] (= "processing" (get-in @app [:status :status])))
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

(defn start! []
  (swap! app assoc :error nil)
  (api/POST "/api/record/start"
            #(swap! app assoc :status %)
            #(swap! app assoc :error (or (get-in % [:response :error]) "could not start"))))

(defn stop! []
  (api/POST "/api/record/stop"
            (fn [_] (fetch-status!))
            #(swap! app assoc :error (or (get-in % [:response :error]) "could not stop"))))

(defn delete! [id]
  (api/DELETE (str "/api/recordings/" id)
              (fn [_]
                (when (= id (:selected @app)) (swap! app assoc :selected nil :peaks nil))
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
