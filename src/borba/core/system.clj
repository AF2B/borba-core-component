(ns borba.core.system
  "Starts and stops the Integrant system that a configuration describes.

   A configuration is a map with the system under :ig/system and, optionally,
   the namespaces to load before it starts under :service/namespaces: the
   namespaces whose methods register components, handlers and the like, which
   nothing else would require."
  (:require
   ;; Registers the :borba/core and :borba/lifecycle components, which every
   ;; service can use without listing this library's namespace.
   [borba.core]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(set! *warn-on-reflection* true)

(defonce ^:private running (atom nil))

(defn require-namespaces
  "Loads namespaces by name, and fails naming the first one that cannot be
   loaded, so a typo in the configuration is not a missing handler found later.
   - namespaces: the namespace symbols to load"
  [namespaces]
  (doseq [namespace-symbol namespaces]
    (try
      (require namespace-symbol)
      (catch java.io.FileNotFoundException cause
        (throw (ex-info "a namespace of the configuration cannot be loaded"
                        {:error     ::namespace-not-found
                         :namespace namespace-symbol}
                        cause))))))

(defn init
  "Loads the namespaces of a configuration and initialises its system, and
   returns the running system. When a component fails to start, the ones that
   had started are halted before the failure is thrown.
   - config: a configuration map with :ig/system and :service/namespaces"
  [config]
  (when-not (map? (:ig/system config))
    (throw (ex-info "the configuration has no :ig/system to start"
                    {:error ::no-system})))
  (require-namespaces (:service/namespaces config))
  (try
    (ig/init (:ig/system config))
    (catch clojure.lang.ExceptionInfo failure
      (when-let [started (:system (ex-data failure))]
        (ig/halt! started))
      (throw failure))))

(defn halt
  "Halts a system, in the reverse order it was started in.
   - system: a system as returned by `init`"
  [system]
  (ig/halt! system))

(defn current
  "Returns the system that `start!` started, or nil when none is running."
  []
  @running)

(defn start!
  "Initialises the system of a configuration and keeps it as the running one.
   A process runs one system, so this fails when one is already running.
   - config: a configuration map with :ig/system and :service/namespaces"
  [config]
  (when (current)
    (throw (ex-info "a system is already running"
                    {:error ::already-running})))
  (let [system (init config)]
    (reset! running system)
    (log/info "system started, components:" (count system))
    system))

(defn stop!
  "Halts the running system, if there is one. Calling it again does nothing."
  []
  (when-let [system (first (reset-vals! running nil))]
    (halt system)
    (log/info "system stopped")))
