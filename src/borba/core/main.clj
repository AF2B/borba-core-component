(ns borba.core.main
  "The entry point of a Borba service:

     clojure -M -m borba.core.main prod

   It reads config.edn for the profile (the first argument, or the PROFILE
   environment variable), starts the system, and stops it cleanly when the
   process is told to terminate. A service that cannot start exits with a
   status the caller can act on."
  (:gen-class)
  (:require
   [borba.core.config :as config]
   [borba.core.system :as system]
   [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(def ^:private profile-env-var "PROFILE")
(def ^:private ^String shutdown-thread-name "borba-shutdown")
(def ^:private exit-failed-to-start 1)
(def ^:private exit-no-profile 2)

(defn resolve-profile
  "Returns the profile to run with as a keyword, taken from the first argument
   or else from the PROFILE environment variable, or nil when there is none.
   - args: the command line arguments
   - environment: a map of environment variable names to values"
  [args
   environment]
  (some-> (or (first args) (get environment profile-env-var))
          not-empty
          keyword))

(defn shutdown-hook
  "Returns the thread the JVM runs when the process is terminated, which stops
   the running system."
  []
  (Thread. (reify Runnable
             (run [_] (system/stop!)))
           shutdown-thread-name))

(defn run
  "Starts the service and returns its system. Unless told otherwise it
   registers a hook that stops the system when the process is terminated.
   - profile: the profile to read the configuration for
   - source: where the configuration comes from (default: config.edn on the
     classpath)
   - hook?: whether to stop the system when the process terminates (default
     true)"
  [{:keys [profile source hook?] :or {hook? true}}]
  (let [loaded (if source
                 (config/read-config source profile)
                 (config/load-config profile))
        system (system/start! loaded)]
    (when hook?
      (.addShutdownHook (Runtime/getRuntime) (shutdown-hook)))
    system))

(defn start
  "Starts the service as the command line asks and returns what happened: the
   system when it started, or a map with the :exit status when it could not.
   - args: the command line arguments
   - environment: a map of environment variable names to values"
  [args
   environment]
  (if-let [profile (resolve-profile args environment)]
    (try
      (run {:profile profile})
      (catch Throwable cause
        (log/error cause "the service failed to start")
        {:exit exit-failed-to-start}))
    (do (log/error "no profile: pass one as the first argument or set"
                   profile-env-var)
        {:exit exit-no-profile})))

(defn -main
  "Starts the service and keeps the process alive until it is terminated.
   - args: the profile, as the first argument"
  [& args]
  (let [result (start args (System/getenv))]
    (if-let [status (:exit result)]
      (System/exit status)
      @(promise))))
