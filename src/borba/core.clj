(ns borba.core
  "The Integrant components that give a service its lifecycle.

   `:borba/lifecycle` holds the state that readiness reports, see
   borba.core.lifecycle. `:borba/core` marks the service ready once everything
   it comes after is up, and on the way down turns readiness off and waits for
   a load balancer to notice before the rest of the system halts. Integrant
   halts in the reverse order of the start, so a component that `:after` lists
   is still running while the service drains, and stops only when the service
   has stopped taking requests.

     :borba/lifecycle {}

     :borba/core
     {:service-name   \"payment-service\"
      :version        #or [#env APP_VERSION \"dev\"]
      :profile        :prod
      :lifecycle      #ig/ref :borba/lifecycle
      :drain-delay-ms 5000
      :after          [#ig/ref :server/http]}"
  (:require
   [borba.core.lifecycle :as lifecycle]
   [clojure.tools.logging :as log]
   [integrant.core :as ig])
  (:import
   (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def ^:private default-service-name "borba-service")
(def ^:private default-drain-delay-ms 0)

(defmethod ig/init-key :borba/lifecycle
  [_ _options]
  (lifecycle/create))

(defmethod ig/halt-key! :borba/lifecycle
  [_ state]
  (lifecycle/mark-stopped! state))

(defmethod ig/init-key :borba/core
  [_ {:keys [service-name version profile lifecycle drain-delay-ms]
      :or   {service-name   default-service-name
             drain-delay-ms default-drain-delay-ms}}]
  (when lifecycle
    (lifecycle/mark-ready! lifecycle))
  (log/infof "%s started (version %s, profile %s)"
             service-name (or version "dev") (some-> profile name))
  {:service-name   service-name
   :lifecycle      lifecycle
   :drain-delay-ms drain-delay-ms
   :started-at     (Instant/now)})

(defmethod ig/halt-key! :borba/core
  [_ {:keys [service-name lifecycle drain-delay-ms started-at]}]
  (when lifecycle
    (lifecycle/begin-drain! lifecycle))
  (log/infof "%s is draining for %d ms" service-name drain-delay-ms)
  (let [delay-ms (long drain-delay-ms)]
    (when (pos? delay-ms)
      (Thread/sleep delay-ms)))
  (log/infof "%s stopped after %d s" service-name
             (.getSeconds (Duration/between ^Instant started-at
                                            (Instant/now)))))
