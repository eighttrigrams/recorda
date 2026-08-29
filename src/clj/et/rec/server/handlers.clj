(ns et.rec.server.handlers
  "Every HTTP handler recorda has.

   Each docstring opens with `METHOD /path — …`, which is the convention the
   sibling apps follow and which /api/describe parses to publish the route
   list. It is prose doing double duty, so the first line of a docstring here
   is not free-form."
  (:require [et.rec.capture :as capture]
            [et.rec.config :as config]
            [et.rec.devices :as devices]
            [et.rec.media :as media]
            [et.rec.store :as store]))

(defn- ok [body] {:status 200 :body body})
(defn- bad [body] {:status 400 :body body})

(defn devices-handler
  "GET /api/devices — the screens and audio inputs AVFoundation can see, and
  which of them recorda would use for the next take."
  [_req]
  (ok (devices/report (config/config))))

(defn refresh-devices-handler
  "POST /api/devices/refresh — re-measure the screens. Costs a second or two
  per display because each is opened for a frame; worth it after plugging one
  in, pointless otherwise."
  [_req]
  (if (= :idle (:status (capture/status)))
    (do (devices/screens true)
        (ok (devices/report (config/config))))
    (bad {:error "cannot re-probe screens while recording"})))

(defn status-handler
  "GET /api/status — whether a take is running, and how long it has been."
  [_req]
  (ok (capture/status)))

(defn start-handler
  "POST /api/record/start — begin a take."
  [_req]
  (let [r (capture/start!)]
    (if (:error r) (bad r) (ok r))))

(defn stop-handler
  "POST /api/record/stop — end the take and split it into its two tracks."
  [_req]
  (let [r (capture/stop!)]
    (if (:error r) (bad r) (ok r))))

(defn list-handler
  "GET /api/recordings — every take, newest first."
  [_req]
  (ok (store/list-recordings)))

(defn get-handler
  "GET /api/recordings/:id — one take's metadata."
  [req]
  (if-let [m (store/read-meta (get-in req [:params :id]))]
    (ok m)
    {:status 404 :body {:error "no such recording"}}))

(defn rename-handler
  "PUT /api/recordings/:id — set a take's title."
  [req]
  (let [id    (get-in req [:params :id])
        title (get-in req [:body :title])]
    (cond
      (nil? (store/read-meta id)) {:status 404 :body {:error "no such recording"}}
      (empty? title)              (bad {:error "title required"})
      :else (ok (store/update-meta! id assoc :title title)))))

(defn delete-handler
  "DELETE /api/recordings/:id — remove a take and its files."
  [req]
  (let [id (get-in req [:params :id])]
    (if (= id (:id (capture/status)))
      (bad {:error "that take is still recording"})
      (if (store/delete! id)
        (ok {:deleted id})
        {:status 404 :body {:error "no such recording"}}))))

(defn media-handler
  "GET /media/:id/:name — a take's own files, with Range support so the video
  element can seek. Not under /api because it does not speak JSON."
  [req]
  (let [{:keys [id name]} (:params req)]
    ;; The name is matched against a fixed set rather than joined onto the
    ;; recordings path, so "../../secrets.yaml" is not a filename this can be
    ;; talked into serving. The server binds to loopback, but a path traversal
    ;; reachable from any page the browser has open is not a loopback problem.
    (if-not (#{"video.mp4" "audio.wav" "peaks.json" "capture.mkv" "ffmpeg.log"} name)
      {:status 404 :body "no such file"}
      (media/file-response (store/file id name) (get-in req [:headers "range"])))))
