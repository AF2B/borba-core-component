(ns borba.core.lifecycle
  "The state of a running service, which readiness reports and shutdown moves
   through, in one direction:

     :starting -> :ready -> :draining -> :stopped

   A service is ready when it can take requests, draining when it has been told
   to stop and is finishing what it accepted (so a load balancer should stop
   sending it more), and stopped when it is done. A service that fails to
   start goes from :starting straight to :stopped.")

(def ^:private transitions
  {:starting #{:ready :stopped}
   :ready    #{:draining :stopped}
   :draining #{:stopped}
   :stopped  #{}})

(defn create
  "Returns the state of a service that is starting."
  []
  (atom :starting))

(defn state
  "Returns the current state: :starting, :ready, :draining or :stopped.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  @lifecycle)

(defn ready?
  "Returns true when the service can take requests.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  (= :ready (state lifecycle)))

(defn draining?
  "Returns true when the service is finishing what it accepted and is about
   to stop.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  (= :draining (state lifecycle)))

(defn stopped?
  "Returns true when the service is done.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  (= :stopped (state lifecycle)))

(defn transition!
  "Moves to a state, atomically, and throws when the move goes against the
   direction of the lifecycle. Moving to the state it is already in does
   nothing, so shutdown can be asked for twice.
   - lifecycle: a lifecycle as returned by `create`
   - target: the state to move to"
  [lifecycle
   target]
  (let [[_ current] (swap-vals! lifecycle
                                (fn [current]
                                  (if (contains? (transitions current) target)
                                    target
                                    current)))]
    (when-not (= current target)
      (throw (ex-info "a service cannot move to that state from this one"
                      {:error ::invalid-transition
                       :from  current
                       :to    target})))
    lifecycle))

(defn mark-ready!
  "Records that the service can take requests.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  (transition! lifecycle :ready))

(defn begin-drain!
  "Records that the service is stopping: readiness turns false while what was
   accepted is finished.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  (transition! lifecycle :draining))

(defn mark-stopped!
  "Records that the service is done.
   - lifecycle: a lifecycle as returned by `create`"
  [lifecycle]
  (transition! lifecycle :stopped))
