(ns et.rec.ui.api
  "Thin wrappers over cljs-ajax. No auth header, unlike the sibling apps —
   recorda has no accounts to send one for."
  (:require [ajax.core :as ajax]))

(defn- opts [handler error]
  {:format          :json
   :response-format :json
   :keywords?       true
   :handler         handler
   :error-handler   (or error (fn [e] (js/console.error "recorda api" (clj->js e))))})

(defn GET [url handler & [error]] (ajax/GET url (opts handler error)))

(defn POST [url handler & [error]] (ajax/POST url (opts handler error)))

(defn PUT [url body handler & [error]]
  (ajax/PUT url (assoc (opts handler error) :params body)))

(defn DELETE [url handler & [error]] (ajax/DELETE url (opts handler error)))
