(ns borba.core
  "Integrant component for service startup lifecycle.

   Registers :borba/core which:
     - Prints a startup banner with service name, version, profile and timestamp
     - Logs each Integrant key that will be initialised
     - Records started-at for uptime tracking
     - Prints a clean shutdown message on halt

   Declare it in your system EDN and make it depend on other components
   via ig/ref so it runs AFTER the rest of the system is up:

     :borba/core
     {:service-name \"payment-service\"
      :version      #or [#env APP_VERSION \"dev\"]
      :profile      #or [#env PROFILE \"stag\"]
      :components   [:components/database :components/redis]}

   Or with a minimal config (just name + env):

     :borba/core
     {:service-name \"payment-service\"}"
  (:require [integrant.core :as ig])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

;; ── Formatting helpers ───────────────────────────────────────────────────────

(def ^:private box-width 58)

(defn- pad-right [s width]
  (let [len (count s)]
    (if (>= len width)
      (subs s 0 width)
      (str s (apply str (repeat (- width len) " "))))))

(defn- box-line [text]
  (str "║  " (pad-right text (- box-width 4)) "║"))

(defn- format-now []
  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z")
           (.atZone (Instant/now) (ZoneId/systemDefault))))

;; ── Startup banner ───────────────────────────────────────────────────────────

(defn- print-banner
  [{:keys [service-name version profile]}]
  (let [border (apply str (repeat box-width "═"))]
    (println "")
    (println (str "╔" border "╗"))
    (println (box-line (str "▶  " service-name)))
    (println (box-line ""))
    (println (box-line (str "   version  : " (or version "dev"))))
    (println (box-line (str "   profile  : " (or profile "stag"))))
    (println (box-line (str "   started  : " (format-now))))
    (println (str "╚" border "╝"))
    (println "")))

(defn- print-components [component-keys]
  (when (seq component-keys)
    (println "  Active components:")
    (doseq [k component-keys]
      (println "    ✓" (if (keyword? k) (str (namespace k) "/" (name k)) (str k))))
    (println "")))

;; ── Integrant lifecycle ──────────────────────────────────────────────────────

(defmethod ig/init-key :borba/core
  [_ {:keys [service-name version profile components]}]
  (print-banner {:service-name (or service-name "borba-service")
                 :version      version
                 :profile      profile})
  (print-components components)
  (println "✅ System ready — listening for requests.")
  (println "")
  {:service-name service-name
   :started-at   (Instant/now)})

(defmethod ig/halt-key! :borba/core
  [_ {:keys [service-name started-at]}]
  (let [uptime-ms (when started-at
                    (- (.toEpochMilli (Instant/now))
                       (.toEpochMilli ^Instant started-at)))]
    (println "")
    (println (str "⏹  " (or service-name "borba-service") " stopped."
                  (when uptime-ms
                    (str " (uptime: " (quot uptime-ms 1000) "s)"))))))
