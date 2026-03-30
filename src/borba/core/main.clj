(ns borba.core.main
  "Main entry point for Borba microservices.

   Reads system configuration from config.edn on the classpath via Aero,
   requires all namespaces listed in :service/namespaces (so their
   defmethod registrations are loaded), then starts the Integrant system.

   Registers a JVM shutdown hook that calls ig/halt! cleanly.

   ── Usage ────────────────────────────────────────────────────────────────────

     clojure -M:run [profile]

   Profile defaults to 'stag' if not provided.

   ── :service/namespaces ──────────────────────────────────────────────────────

   In system/base.edn, declare every namespace that contains defmethod
   registrations (ig/init-key, ig/halt-key!, handlers/handler, etc.):

     :service/namespaces
     [borba.core
      borba.sql-client
      borba.redis
      borba.kafka-producer
      borba.kafka-consumer
      borba.event-store
      borba.handlers.registry
      borba.handlers.component
      borba.routes.component
      borba.server.component
      borba.railway
      com.example.my-service.handlers.http.routes
      com.example.my-service.events.consumer]"
  (:require [integrant.core :as ig]
            [aero.core :as aero]
            [clojure.java.io :as io]))

(defn -main [& args]
  (let [profile (keyword (or (first args) "stag"))
        config  (aero/read-config (io/resource "config.edn") {:profile profile})
        nss     (:service/namespaces config)]
    (println (str "▶  Loading namespaces for profile: " (name profile)))
    (doseq [ns-sym nss]
      (require ns-sym))
    (println (str "▶  Starting system..."))
    (let [system (ig/init (:ig/system config))]
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread.
        #(do (println "\n⏹  Shutdown signal received. Halting system...")
             (ig/halt! system)
             (println "⏹  System halted."))
        "borba-shutdown"))
      system)))
