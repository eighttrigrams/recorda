(ns et.rec.ui.views.library
  "Every take, newest first."
  (:require [et.rec.ui.state :as state]))

(defn- fmt-dur [d]
  (let [s (js/Math.round (or d 0))]
    (str (js/Math.floor (/ s 60)) ":" (when (< (mod s 60) 10) "0") (mod s 60))))

(defn library []
  (let [{:keys [recordings selected]} @state/app]
    [:div.panel.library
     [:h2 "Takes"]
     (if (empty? recordings)
       [:div.empty "Nothing recorded yet."]
       (for [t recordings]
         ^{:key (:id t)}
         [:div.take {:class    (when (= (:id t) selected) "selected")
                     :on-click #(state/select! (:id t))}
          [:div.title (:title t)]
          [:div.sub {:class (when (not= "ready" (:status t)) "busy")}
           (if (= "ready" (:status t))
             (str (fmt-dur (:duration t)) " · " (:width t) "×" (:height t)
                  (when-let [db (:peak-dbfs t)] (str " · " (js/Math.round db) " dB")))
             (:status t))]
          (when (= (:id t) selected)
            [:div {:style {:margin-top "6px" :display "flex" :gap "6px"}}
             [:button.danger
              {:on-click (fn [e]
                           (.stopPropagation e)
                           (when-let [n (js/prompt "Title" (:title t))]
                             (state/rename! (:id t) n)))}
              "rename"]
             [:button.danger
              {:on-click (fn [e]
                           (.stopPropagation e)
                           (when (js/confirm (str "Delete " (:title t) "? The files go too."))
                             (state/delete! (:id t))))}
              "delete"]])]))]))
