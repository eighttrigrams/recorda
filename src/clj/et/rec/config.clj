(ns et.rec.config
  "config.edn, read once at startup.

   The same aero shape the sibling apps use, minus the parts that only mean
   something when an app is deployed. There is no config.prod.edn here and no
   :db — recorda runs in one place only, and its store is a directory."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defonce ^:private *config (atom nil))

(defn load-config!
  "Read config.edn beside the checkout. Unlike the siblings this throws a
   readable error rather than returning {} when the file is missing, because
   there is no environment here in which running without one is meaningful —
   scripts/start.sh copies the template before it ever gets this far."
  []
  (let [f (io/file "config.edn")]
    (when-not (.exists f)
      (throw (ex-info "config.edn not found — copy config.edn.template to config.edn" {})))
    (reset! *config (aero/read-config f))))

(defn config [] @*config)

(defn get-conf
  ([k] (get @*config k))
  ([k default] (get @*config k default)))

(defn recordings-dir
  "Absolute file for the recordings root, created if absent."
  []
  (doto (io/file (get-conf :recordings-dir "recordings"))
    (.mkdirs)))
