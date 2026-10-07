(ns borba.core.system
  "Starts and stops the Integrant system that a configuration describes.

   A configuration is a map with the system under :ig/system and, optionally,
   the namespaces to load before it starts under :service/namespaces: the
   namespaces whose methods register components, handlers and the like, which
   nothing else would require. A component whose namespace was not loaded fails
   the start saying which key, and which namespace of the Borba libraries
   registers it when it is one of theirs."
  (:require
   ;; Registers the :borba/core and :borba/lifecycle components, which every
   ;; service can use without listing this library's namespace.
   [borba.core]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(set! *warn-on-reflection* true)

(defonce ^:private running (atom nil))

(def known-components
  "The namespace of the Borba libraries that registers each of their
   components, to say which one a system is missing."
  {:service/interceptors      'borba.interceptors.component
   :service/handlers          'borba.handlers.component
   :http/routes               'borba.routes.component
   :server/http               'borba.server.component
   :components/database       'borba.sql-client
   :components/redis          'borba.redis
   :components/event-store    'borba.event-store
   :components/kafka-producer 'borba.kafka-producer
   :components/kafka-consumer 'borba.kafka-consumer
   :kafka/consumer-handlers   'borba.kafka-consumer})

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

(defn- unregistered?
  "Returns true when a failure of Integrant is that nothing is registered for
   a key: a namespace that was not loaded, or no function of that name."
  [cause]
  (or (and (instance? IllegalArgumentException cause)
           (str/includes? (str (ex-message cause)) "No such namespace"))
      (= :integrant.core/missing-init-key (:reason (ex-data cause)))))

(defn- registrar
  "Returns the namespace of the Borba libraries that registers a key, or nil.
   A composite key is registered by the last of its keywords."
  [component]
  (known-components (if (vector? component)
                      (peek component)
                      component)))

(defn- explain
  "Returns the exception to throw for a failure of Integrant. For a key that
   nothing is registered for, which Integrant reports as a namespace that does
   not exist, it says which key it is and what is to be done."
  [failure]
  (let [component (:key (ex-data failure))]
    (if (and component (unregistered? (ex-cause failure)))
      (let [namespace-symbol (registrar component)]
        (ex-info (str "no component is registered for " component
                      ": the namespace that registers it is not loaded; list "
                      (or namespace-symbol "it")
                      " under :service/namespaces")
                 {:error     ::unregistered-component
                  :key       component
                  :namespace namespace-symbol}
                 failure))
      failure)))

(defn init
  "Loads the namespaces of a configuration and initialises its system, and
   returns the running system. When a component fails to start, the ones that
   had started are halted before the failure is thrown. A component that
   nothing is registered for fails the start naming its key.
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
      (throw (explain failure)))))

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
