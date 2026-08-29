(ns et.rec.devices
  "What AVFoundation can see, and which of it recorda should use.

   **Nothing here is remembered as an index.** AVFoundation numbers devices in
   enumeration order, so waking the OBSBOT or plugging in the iPhone renumbers
   the cameras — and the screens, which are enumerated after them, shift with
   it. On this machine the small display was index 5 with four cameras
   attached; unplug two and it is 3. An index written into a config file is
   therefore right until the next time something is plugged in, and then wrong
   *silently*: you find out by recording the wrong display for twenty minutes.

   So the screen is chosen by **measurement** — every screen is opened for one
   frame and asked how big it is — and the microphone by **name**. Both are
   facts that survive renumbering."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- run
  "ffmpeg writes what we want to stderr and exits non-zero doing it, which is
   not a failure: there was no input to open, and saying so is the whole job.
   The exit code is noise here, the streams are the result."
  [& args]
  (let [{:keys [out err]} (apply shell/sh args)]
    (str out "\n" err)))

(defn enumerate
  "Every AVFoundation device, split by kind:
   {:video [{:index 4 :name \"Capture screen 0\"} …] :audio [{…}]}"
  []
  (let [text (run "ffmpeg" "-hide_banner" "-f" "avfoundation"
                  "-list_devices" "true" "-i" "")]
    (loop [[line & more] (str/split-lines text)
           section nil
           acc     {:video [] :audio []}]
      (cond
        (nil? line) acc
        (str/includes? line "AVFoundation video devices") (recur more :video acc)
        (str/includes? line "AVFoundation audio devices") (recur more :audio acc)
        :else
        ;; The log prefix is "[AVFoundation indev @ 0x7f…]", which cannot match
        ;; a bracket holding nothing but digits — so this finds the device
        ;; marker and not the prefix, without having to strip the prefix first.
        (if-let [[_ idx nm] (re-find #"\[(\d+)\] (.+)$" line)]
          (recur more section
                 (cond-> acc
                   section (update section conj {:index (parse-long idx)
                                                 :name  (str/trim nm)})))
          (recur more section acc))))))

(defn- screen? [{:keys [name]}]
  (str/starts-with? name "Capture screen"))

(defn- probe-size
  "Open a screen for exactly one frame and read its dimensions back out of
   ffmpeg's own stream description. Costs a second or two per screen and needs
   Screen Recording permission — which is worth discovering here, at a button
   the user pressed, rather than at the start of a take."
  [index]
  (let [text (run "ffmpeg" "-hide_banner" "-f" "avfoundation" "-i" (str index)
                  "-frames:v" "1" "-f" "null" "-")]
    (if-let [[_ w h] (re-find #"Stream #0:0: Video:.*?, (\d+)x(\d+)" text)]
      {:width (parse-long w) :height (parse-long h)}
      {:error (cond
                (str/includes? text "Operation not permitted")
                "no Screen Recording permission for this terminal"
                :else
                "could not read this screen's size")})))

(defonce ^:private *screens (atom nil))

(defn screens
  "Screens with their measured sizes. Cached, because probing opens each
   capture device in turn; pass true to re-measure after plugging a display in."
  ([] (screens false))
  ([refresh?]
   (if (and (not refresh?) (seq @*screens))
     @*screens
     (reset! *screens
             (mapv #(merge % (probe-size (:index %)))
                   (filter screen? (:video (enumerate))))))))

(defn- area [{:keys [width height]}] (* (or width 0) (or height 0)))

(defn resolve-screen
  "The screen a preference names. :smallest is the one this app exists for.
   An explicit integer is honoured as an AVFoundation index — supported for
   the case where measurement cannot help, and carrying the caveat in the
   namespace docstring above."
  [pref]
  (let [ss (remove :error (screens))]
    (cond
      (integer? pref)  (first (filter #(= pref (:index %)) (screens)))
      (= :largest pref)  (last (sort-by area ss))
      :else              (first (sort-by area ss)))))

(defn resolve-mic
  "The audio device whose name contains `wanted`, case-insensitively — so
   \"Scarlett\" finds \"Scarlett Solo 4th Gen\" and keeps finding it when
   Focusrite renames the 5th."
  [wanted]
  (let [want (str/lower-case (or wanted ""))]
    (->> (:audio (enumerate))
         (filter #(str/includes? (str/lower-case (:name %)) want))
         first)))

(defn report
  "Everything the UI needs to say what it is about to record, and everything
   `make devices` needs to print."
  [{:keys [screen mic-name] :as _conf}]
  (let [all     (enumerate)
        chosen  (resolve-screen screen)
        mic     (resolve-mic mic-name)]
    {:screens  (screens)
     :audio    (:audio all)
     :chosen-screen chosen
     :chosen-mic    mic
     :ready?   (boolean (and chosen (not (:error chosen)) mic))}))

(defn print-report
  "`make devices`. Reads config.edn for the preferences so that what it prints
   is what recorda would actually do, not a second opinion."
  []
  (require 'et.rec.config)
  (let [load!  (resolve 'et.rec.config/load-config!)
        conf   (load!)
        {:keys [screens audio chosen-screen chosen-mic ready?]} (report conf)]
    (println "\nScreens (AVFoundation video devices):")
    (doseq [s screens]
      (println (format "  [%d] %-18s %s%s"
                       (:index s) (:name s)
                       (or (:error s) (format "%dx%d" (:width s) (:height s)))
                       (if (= (:index s) (:index chosen-screen)) "   <- recorda picks this" ""))))
    (println "\nAudio devices:")
    (doseq [a audio]
      (println (format "  [%d] %s%s" (:index a) (:name a)
                       (if (= (:index a) (:index chosen-mic)) "   <- recorda picks this" ""))))
    (println)
    (if ready?
      (println "Ready.")
      (println "NOT ready — see above. A screen with no size usually means this"
               "\nterminal lacks Screen Recording permission"
               "\n(System Settings > Privacy & Security > Screen Recording)."))
    (println)))
