(ns et.rec.server.handlers
  "Every HTTP handler recorda has.

   Each docstring opens with `METHOD /path — …`, which is the convention the
   sibling apps follow and which /api/describe parses to publish the route
   list. It is prose doing double duty, so the first line of a docstring here
   is not free-form."
  (:require [clojure.java.io :as io]
            [et.rec.capture :as capture]
            [et.rec.config :as config]
            [et.rec.devices :as devices]
            [et.rec.assemble :as assemble]
            [et.rec.export :as export]
            [et.rec.ff :as ff]
            [et.rec.media :as media]
            [et.rec.split :as split]
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

(defn create-handler
  "POST /api/recordings — a new, empty project. A project is one video, built
  over as many sittings as it takes; this makes the card, and recording into it
  makes the video."
  [req]
  (let [id (store/new-id)]
    (store/write-meta! id {:id         id
                           :title      (or (not-empty (get-in req [:body :title])) id)
                           :created-at (str (java.time.Instant/now))
                           :status     :empty
                           ;; No :clips. A project without an arrangement reads
                           ;; as one clip per segment, so the empty case needs
                           ;; nothing said about it and the first edit is the
                           ;; only thing that ever writes one.
                           :segments   []})
    {:status 201 :body (store/read-meta id)}))

(defn start-handler
  "POST /api/recordings/:id/record/start — record another sitting onto this
  project. The first one is its opening; every later one lands where `mode`
  says.

  `mode` is `append` (the default — on the end) or `at-playhead` (spliced in at
  `at` seconds, keeping what followed). `at` is a position on the assembly
  timeline. There is no mode for replacing from the playhead: trim and then
  append already is that, in two calls that are each reversible on their own.

  **Nothing is rearranged until the sitting finishes.** The mode is written down
  and applied when the audio lands, so a take that fails or is cancelled leaves
  the project exactly as it was, whatever it was going to do.

  For `at-playhead` the answer's `at` is the *snapped* position: a copy can only
  resume at a keyframe, so the cut lands on the nearest one and the caller is
  told where that is rather than being left to claim an accuracy the format
  does not have."
  [req]
  (let [mode (or (get-in req [:params "mode"])
                 (some-> (get-in req [:body :mode]) name))
        at   (or (some-> (get-in req [:params "at"]) parse-double)
                 (some-> (get-in req [:body :at]) double))
        r    (capture/start! (get-in req [:params :id])
                             {:mode (some-> mode keyword) :at at})]
    (if (:error r) (bad r) (ok r))))

(defn stop-handler
  "POST /api/record/stop — end the take and split it into its two tracks."
  [_req]
  (let [r (capture/stop!)]
    (if (:error r) (bad r) (ok r))))

(defn abandon-handler
  "POST /api/record/abandon — give up on a sitting and let the app record again.

  The escape hatch for a take the browser can no longer finish: the picture is
  captured on the server and the sound in the browser, so a tab that dies
  between the two leaves a take nothing can complete. Without this the app waits
  for audio forever and only a restart clears it.

  Drops the sitting and removes its directory. Nothing already in the project is
  touched — a pending sitting was never part of the assembly."
  [_req]
  (ok (capture/abandon!)))

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

(defn upload-audio-handler
  "POST /api/recordings/:id/segments/:n/audio — the microphone recording for one
  sitting, as a WAV body, with `X-Audio-Lead-Ms` saying how long the mic was
  already running before that segment's first video frame.

  The browser records the sound because ffmpeg's AVFoundation audio input drops
  roughly 11% of it — see the note in et.rec.capture. This is where that
  recording lands and where it is lined up with the picture."
  [req]
  (let [id   (get-in req [:params :id])
        n    (some-> (get-in req [:params :n]) parse-long)
        lead (or (some-> (get-in req [:headers "x-audio-lead-ms"]) parse-long) 0)]
    (cond
      (nil? (store/read-meta id))
      {:status 404 :body {:error "no such recording"}}

      (nil? (:body req))
      (bad {:error "no audio body"})

      :else
      (let [tmp (java.io.File/createTempFile (str "recorda-" id "-") ".wav")]
        (io/copy (:body req) tmp)
        (let [res (try (split/finish-audio! id n tmp lead)
                       (catch Exception e {:ok? false :error (.getMessage e)})
                       (finally (capture/audio-received!)))]
          (if (:ok? res)
            (ok (store/read-meta id))
            (bad {:error (str "could not write audio: " (:error res))})))))))

(defn trim-handler
  "POST /api/recordings/:id/trim — cut the project's tail at `at` seconds on the
  assembly timeline, so the next sitting carries on from there.

  Frame-accurate, because a tail cut needs no keyframe — a stream copy can end
  anywhere. Nothing is thrown away either: the arrangement stops there and every
  file stays whole, so a trim can be undone."
  [req]
  (let [id (get-in req [:params :id])
        ;; Query params arrive from wrap-params string-keyed, while compojure's
        ;; own route params are keywordised — a distinction that has bitten this
        ;; suite before. A JSON body is accepted too.
        at (or (some-> (get-in req [:params "at"]) parse-double)
               (some-> (get-in req [:body :at]) double))]
    (cond
      (nil? (store/read-meta id)) {:status 404 :body {:error "no such project"}}
      (nil? at)                   (bad {:error "at (seconds) required"})
      :else (let [r (assemble/trim-at! id at)]
              (if (:ok? r) (ok (store/read-meta id)) (bad r))))))

(defn undo-handler
  "POST /api/recordings/:id/undo — step back one change to the arrangement.

  One press per change: a trim, a marker, a deleted piece, a sitting recorded
  into the middle. It is undo and not a reset — there is deliberately no button
  that throws every edit away at once, because a control that blunt is one you
  press by accident and cannot take back in kind.

  Possible at all because editing only ever writes an arrangement over the
  sittings and never touches a file, so every state it can step back to is one
  the files can still produce."
  [req]
  (let [id (get-in req [:params :id])]
    (if (nil? (store/read-meta id))
      {:status 404 :body {:error "no such project"}}
      (let [r (assemble/undo! id)]
        (if (:ok? r) (ok (store/read-meta id)) (bad r))))))

(defn delete-clip-handler
  "DELETE /api/recordings/:id/clips/:i — drop one piece from the arrangement.

  `:i` indexes the pieces the project currently plays, which is what the lanes
  draw between their seam marks.

  Nothing is deleted from disk: the sitting behind the piece stays whole, and
  this only stops it being played, so a wrong one costs an `undo edits`. If
  taking the piece out leaves two halves of one sitting meeting end to start
  they are rejoined, and if what remains is every sitting whole and in order the
  arrangement is dropped entirely — deleting an insertion leaves the project as
  though it had never happened."
  [req]
  (let [id (get-in req [:params :id])
        i  (some-> (get-in req [:params :i]) parse-long)]
    (cond
      (nil? (store/read-meta id)) {:status 404 :body {:error "no such project"}}
      (nil? i)                    (bad {:error "which piece?"})
      :else (let [r (assemble/delete-clip! id i)]
              (if (:ok? r) (ok (store/read-meta id)) (bad r))))))

(defn split-handler
  "POST /api/recordings/:id/split — put a marker at `at` seconds, cutting one
  piece into two.

  Changes nothing about what plays. A marker is the handle an edit needs: put
  two round something and the piece between them can be deleted, which is how
  you cut from the middle.

  The answer's `at` is where the marker actually went. A copy can only resume at
  a keyframe, so it lands on the nearest one."
  [req]
  (let [id (get-in req [:params :id])
        at (or (some-> (get-in req [:params "at"]) parse-double)
               (some-> (get-in req [:body :at]) double))]
    (cond
      (nil? (store/read-meta id)) {:status 404 :body {:error "no such project"}}
      (nil? at)                   (bad {:error "at (seconds) required"})
      :else (let [r (assemble/split-at! id at)]
              (if (:ok? r) (ok (store/read-meta id)) (bad r))))))

(defn delete-seam-handler
  "DELETE /api/recordings/:id/seams/:i — remove a marker, so the two pieces
  either side of it become one again.

  Only a marker somebody put there can go. Two pieces of the same sitting
  meeting end to start are continuous material with a mark drawn on it; two
  different sittings meeting is the join itself, and refusing to remove that is
  the honest answer rather than silently welding unrelated material together."
  [req]
  (let [id (get-in req [:params :id])
        i  (some-> (get-in req [:params :i]) parse-long)]
    (cond
      (nil? (store/read-meta id)) {:status 404 :body {:error "no such project"}}
      (nil? i)                    (bad {:error "which marker?"})
      :else (let [r (assemble/delete-seam! id i)]
              (if (:ok? r) (ok (store/read-meta id)) (bad r))))))

(defn export-handler
  "POST /api/recordings/:id/export — mux the take's two tracks into one
  export.mp4 and answer where it is. Regenerated on every call rather than
  cached, because once edits exist the answer depends on them."
  [req]
  (let [id (get-in req [:params :id])]
    (if (nil? (store/read-meta id))
      {:status 404 :body {:error "no such recording"}}
      (let [r (export/export! id)]
        (if (:ok? r)
          (ok (assoc r :url (str "/media/" id "/export.mp4")))
          (bad r))))))

(defn set-crop-handler
  "PUT /api/recordings/:id/crop — the area of the video to keep, as
  {:x :y :w :h} in the video's own pixels. An empty body clears it.

  **Settable at any time, and changeable at any time**, because the crop is
  applied on export rather than during capture. You draw it on the footage you
  actually recorded, and redrawing it costs nothing until you export again."
  [req]
  (let [id   (get-in req [:params :id])
        body (:body req)
        m    (store/read-meta id)]
    (cond
      (nil? m)
      {:status 404 :body {:error "no such project"}}

      (nil? (:w body))
      (do (store/update-meta! id dissoc :crop)
          (ok (store/read-meta id)))

      :else
      (let [video (store/file id "video.mp4")
            vw    (or (some-> (ff/probe video "v:0" "stream=width") parse-long)
                      (:width m))
            vh    (or (some-> (ff/probe video "v:0" "stream=height") parse-long)
                      (:height m))]
        (if-not (and vw vh)
          (bad {:error "record something first — the area is drawn on the video"})
          (let [c (export/normalise-crop body vw vh)]
            (store/update-meta! id assoc :crop c)
            (ok (store/read-meta id))))))))

(defn media-handler
  "GET /media/:id/:name — a take's own files, with Range support so the video
  element can seek. Not under /api because it does not speak JSON."
  [req]
  (let [{:keys [id name]} (:params req)]
    ;; The name is matched against a fixed set rather than joined onto the
    ;; recordings path, so "../../secrets.yaml" is not a filename this can be
    ;; talked into serving. The server binds to loopback, but a path traversal
    ;; reachable from any page the browser has open is not a loopback problem.
    (if-not (#{"video.mp4" "audio.wav" "peaks.json" "capture.mkv" "ffmpeg.log" "export.mp4"} name)
      {:status 404 :body "no such file"}
      (media/file-response
        (store/file id name)
        (get-in req [:headers "range"])
        ;; The three derived assets are written once and never touched again,
        ;; so they cache forever. The capture, the log and the export are not:
        ;; one is appended to while a take runs, one is a debugging aid you
        ;; want the current contents of, and the export is rebuilt on every
        ;; request because once edits exist its content depends on them.
        (if (#{"video.mp4" "audio.wav" "peaks.json"} name)
          "private, max-age=31536000, immutable"
          "no-cache")))))
