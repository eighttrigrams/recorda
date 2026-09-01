(ns et.rec.server
  "Routes, middleware and the process entry point.

   There is no wrap-auth here and no rate limiter, which is the visible
   difference from every sibling in this workspace. recorda binds to loopback,
   has one user, and holds nothing that is not already a file in the operator's
   home directory. An identity system would guard the door of a room with no
   walls — and would then need a login page, a token, and somewhere to keep a
   password hash, all to protect a screen recording from its own author."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [defroutes context GET POST PUT DELETE]]
            [compojure.route :as route]
            [et.rec.config :as config]
            [et.rec.server.handlers :as h]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; /api/describe — the same self-description the sibling apps publish.

(def ^:private route-doc-re #"(?s)^(GET|POST|PUT|DELETE|PATCH)\s+(\S+)\s")

(defn- describe []
  (->> (ns-publics 'et.rec.server.handlers)
       vals
       (keep (fn [v]
               (let [m (meta v)]
                 (when-let [[_ method path] (re-find route-doc-re (str (:doc m)))]
                   {:name (str (:name m)) :method method :path path :doc (:doc m)}))))
       (sort-by (juxt :path :method))
       vec))

(defn describe-handler
  "GET /api/describe — every route this app serves."
  [_req]
  {:status 200 :body {:app "recorda" :endpoints (describe)}})

;; ---------------------------------------------------------------------------

(defroutes api-routes
  (context "/api" []
    (GET    "/describe"        [] describe-handler)
    (GET    "/devices"         [] h/devices-handler)
    (POST   "/devices/refresh" [] h/refresh-devices-handler)
    (GET    "/status"          [] h/status-handler)
    (POST   "/record/stop"     [] h/stop-handler)
    (POST   "/record/abandon"  [] h/abandon-handler)
    (GET    "/recordings"      [] h/list-handler)
    (POST   "/recordings"      [] h/create-handler)
    (GET    "/recordings/:id"  [] h/get-handler)
    (PUT    "/recordings/:id"  [] h/rename-handler)
    (DELETE "/recordings/:id"  [] h/delete-handler)
    ;; Raw WAV in, not JSON. wrap-json-body leaves a non-JSON content type
    ;; alone, so the body arrives here as the stream it was sent as.
    (POST   "/recordings/:id/record/start"   [] h/start-handler)
    (POST   "/recordings/:id/segments/:n/audio" [] h/upload-audio-handler)
    (PUT    "/recordings/:id/crop"   [] h/set-crop-handler)
    (GET    "/recordings/:id/redact"      [] h/redact-handler)
    (PUT    "/recordings/:id/redact"      [] h/set-redact-handler)
    (POST   "/recordings/:id/redact/scan" [] h/scan-redact-handler)
    (POST   "/recordings/:id/trim"   [] h/trim-handler)
    (POST   "/recordings/:id/export" [] h/export-handler)
    (POST   "/recordings/:id/split"    [] h/split-handler)
    (DELETE "/recordings/:id/clips/:i" [] h/delete-clip-handler)
    (DELETE "/recordings/:id/seams/:i" [] h/delete-seam-handler)
    ;; Raw bytes in, like the audio upload above and for the same reason.
    (POST   "/recordings/:id/music"      [] h/add-music-handler)
    (POST   "/recordings/:id/music/sample" [] h/add-sample-music-handler)
    (PUT    "/recordings/:id/music/:cid" [] h/move-music-handler)
    (DELETE "/recordings/:id/music/:cid" [] h/delete-music-handler)
    (PUT    "/recordings/:id/gain"       [] h/set-gain-handler)))

(defn- cache-bust []
  (let [f (io/file "resources/public/recorda/js/main.js")]
    (str (if (.exists f) (.lastModified f) (System/currentTimeMillis)))))

(defn- serve-with-bust [path content-type]
  (fn [_req]
    (let [f (io/file (str "resources/public/recorda/" path))]
      (if (.exists f)
        {:status 200
         :headers {"Content-Type" content-type "Cache-Control" "no-cache"}
         :body (str/replace (slurp f) "__CACHE_BUST__" (cache-bust))}
        {:status 404 :body "not built — run npx shadow-cljs watch app"}))))

(defroutes page-routes
  ;; Media is deliberately outside the JSON middleware: its body is a stream
  ;; over a video file, and wrap-json-response would be asked to serialise it.
  (GET "/media/:id/music/:cid/:what" [] h/music-media-handler)
  (GET "/media/:id/:name" [] h/media-handler)
  (GET "/" [] (serve-with-bust "index.html" "text/html"))
  (GET "/styles.css" [] (serve-with-bust "styles.css" "text/css"))
  (route/resources "/" {:root "public/recorda"})
  (route/not-found {:status 404 :body "not found"}))

(def app
  (-> (compojure.core/routes
        (-> api-routes
            (wrap-json-body {:keywords? true})
            wrap-json-response)
        page-routes)
      wrap-params))

(defn -main [& _]
  (config/load-config!)
  (let [{:keys [port nrepl-port logfile]} (config/config)]
    (when logfile
      (io/make-parents logfile)
      (t/add-handler! :file (t/handler:file {:path logfile})))
    (config/recordings-dir)
    (when nrepl-port
      (require 'nrepl.server)
      ((resolve 'nrepl.server/start-server) :port nrepl-port :bind "127.0.0.1")
      (spit ".nrepl-port" (str nrepl-port)))
    (t/log! :info (str "recorda on http://127.0.0.1:" port))
    (println (str "recorda on http://127.0.0.1:" port))
    ((requiring-resolve 'ring.adapter.jetty9/run-jetty) app {:port port :host "127.0.0.1" :join? false})
    @(promise)))
