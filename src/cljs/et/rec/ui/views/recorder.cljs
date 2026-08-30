(ns et.rec.ui.views.recorder
  "The strip along the top: what will be recorded, and the button that does it."
  (:require [et.rec.ui.mic :as mic]
            [et.rec.ui.state :as state]
            [reagent.core :as r]))

(defonce ^:private now (r/atom (js/Date.now)))
(defonce ^:private _clock (js/setInterval #(reset! now (js/Date.now)) 100))

(defn- fmt-elapsed [secs]
  (let [s (js/Math.floor secs)]
    (str (js/Math.floor (/ s 60)) ":" (when (< (mod s 60) 10) "0") (mod s 60))))

(defn- meter-pos
  "dBFS to a 0..100 position. The scale runs -60 to 0 because everything worth
   seeing on a voice track lives there, and a linear amplitude bar spends most
   of its width on the top 6 dB where nothing interesting happens."
  [db]
  (max 0 (min 100 (* 100 (/ (+ db 60) 60)))))

(defn level-meter
  "Peak with a slow-decaying hold mark.

   The hold is the useful number: speech peaks are brief and a bar without one
   is unreadable at a glance. Aim the hold at about -12 dBFS. Below -30 the
   take will be all preamp noise when you turn it up; above -3 you are close to
   clipping, which cannot be undone."
  []
  (let [{:keys [peak hold recording? monitoring?]} @mic/state
        live? (or recording? monitoring?)
        pdb   (mic/dbfs peak)
        hdb   (mic/dbfs hold)
        ;; Aim peaks at about -12 dBFS. Below -20 the take is mostly preamp
        ;; noise once it is turned up to a normal level, which is the state
        ;; every recording made here has been in so far; above -3 there is no
        ;; headroom left for a loud syllable and clipping cannot be undone.
        verdict (cond (not live?) nil
                      (< hdb -20) ["low" "low — turn the interface gain up"]
                      (> hdb -3)  ["hot" "hot — back the gain off"]
                      :else       ["good" "good — aim peaks near -12"])]
    [:span.meter-wrap
     [:span.meter {:class (when live? "live")}
      [:span.meter-fill {:style {:width (str (meter-pos pdb) "%")}}]
      (when (and live? (> hold 0))
        [:span.meter-hold {:style {:left (str (meter-pos hdb) "%")}}])]
     (when live?
       [:span.meter-read {:class (first verdict)}
        (if (> hold 0) (str (js/Math.round hdb) " dB") "—")
        " " (second verdict)])]))

(defn devices-strip []
  (let [{:keys [chosen-screen chosen-mic ready?]} (:devices @state/app)
        m (:device @mic/state)
        e (:error @mic/state)]
    [:div.devices {:class (when-not ready? "not-ready")}
     (if chosen-screen
       [:span.dev "Screen "
        [:strong (if (:error chosen-screen)
                   (:error chosen-screen)
                   (str (:width chosen-screen) "×" (:height chosen-screen)))]]
       [:span.dev "Screen " [:strong "none found"]])
     ;; The microphone is the browser's, not ffmpeg's — see et.rec.ui.mic for
     ;; why. Until permission is given its real label is unknown, so the name
     ;; recorda will look for is shown instead.
     [:span.dev "Mic "
      [:strong (or m (:name chosen-mic) "default")]
      (when-not m [:span {:style {:opacity 0.55}} " (browser)"])]
     [level-meter]
     (when-not (state/busy?)
       [:button.danger
        {:on-click #(if (:monitoring? @mic/state)
                      (mic/stop-monitor!)
                      (mic/monitor! (:name chosen-mic)))
         :title "Open the mic and watch the level, so the gain can be set before recording"}
        (if (:monitoring? @mic/state) "stop check" "check level")])
     (when e [:span.dev {:style {:color "var(--record)"}} e])
     [:button.danger {:on-click #(state/refresh-devices!)
                      :disabled (state/busy?)
                      :title "Re-measure the screens — needed after plugging a display in"}
      "rescan"]]))

(defn- fmt-at [t]
  (let [t (max 0 (or t 0))
        m (js/Math.floor (/ t 60))
        sec (js/Math.floor (mod t 60))]
    (str m ":" (when (< sec 10) "0") sec "." (js/Math.floor (* 10 (mod t 1))))))

(def modes
  "Two, not three. Replacing from the playhead is Trim to playhead followed by
   Append — two presses that are each reversible on their own — so a mode for it
   would be a third way to do a thing that already has one."
  [[:append      "Append"      "New material goes on the end. The default, and what a screencast usually wants."]
   [:at-playhead "At playhead" "Record into the middle: the new material goes in at the playhead and what followed is kept. The cut lands on the nearest keyframe, within about 0.2 s."]])

(defn mode-picker
  "Where the next sitting lands.

   It sits beside Record because it is a property of the press, not of the
   project, and it resets to Append after every take — a mode that quietly
   survives is a mode that eats material while you think you are adding to the
   end."
  []
  (let [m (or (:record-mode @state/app) :append)]
    [:div.mode-picker
     (for [[k label title] modes]
       ^{:key (name k)}
       [:button.mode {:class    (when (= k m) "on")
                      :disabled (state/busy?)
                      :title    title
                      :on-click #(swap! state/app assoc :record-mode k)}
        label])
     (when-not (= :append m)
       [:span.mode-at
        ;; While a take runs this is the server's answer — the keyframe it will
        ;; really cut at, which is not always the one that was asked for. Saying
        ;; the requested time here would be a small lie the format cannot
        ;; honour.
        (if-let [landed (:record-at @state/app)]
          (str "at " (fmt-at landed))
          (str "at " (fmt-at (:time @state/app))))])]))

(defn record-button
  "Records another sitting onto the given project. There is no global record
   button: a project is one video, and recording is something you do inside
   one — the first press is its opening, and every later press lands where the
   mode beside it says."
  [project-id]
  (let [rec?  (state/recording?)
        proc? (state/processing?)
        ready (get-in @state/app [:devices :ready?])
        mode  (or (:record-mode @state/app) :append)
        ;; Read off the project, not off a `:segment-count` nobody ever set —
        ;; which is what this used to do, so the button said "Record" forever
        ;; and every other label here was unreachable.
        first? (empty? (remove #(or (:dropped %) (:pending %))
                               (:segments (state/selected-take))))]
    [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
     (when rec?
       (let [started (get-in @state/app [:status :started-at])]
         [:span.elapsed (fmt-elapsed (/ (- @now started) 1000))]))
     (when proc?
       [:span.processing-note
        (if (:uploading? @state/app) "saving audio…" "finishing…")])
     ;; The escape hatch. The picture is captured on the server and the sound
     ;; here, so a tab that dies between the two leaves a take nothing can
     ;; finish — and since waiting is not idle, nothing could be recorded again.
     ;; Only shown once the wait has clearly stopped being the ordinary one.
     (when (state/stuck?)
       [:span.processing-note {:style {:color "var(--record)"}}
        "no audio is coming for that take "
        [:button.danger {:on-click #(state/abandon!)
                         :title "Drop the unfinished sitting and record again. Nothing already in the project is touched."}
         "give up"]])
     [:button.record-btn {:class    (str (when rec? "recording recording-now"))
                          :disabled (or proc? (and (not rec?) (or (not ready) (nil? project-id))))
                          :on-click #(if rec? (state/stop!) (state/start! project-id))}
      [:span.dot]
      ;; The label says which of the three it is about to do, because the
      ;; difference between adding to the end and replacing the end is not
      ;; something to leave to a highlighted button elsewhere on the row.
      (cond rec?   "Stop"
            proc?  "Working"
            first? "Record"
            (= :at-playhead mode) "Record here"
            :else  "Record more")]]))
