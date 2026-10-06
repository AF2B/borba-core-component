(ns borba.core-test
  (:require
   [borba.core]
   [borba.core.lifecycle :as lifecycle]
   [borba.core.logging :as logging]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]))

(set! *warn-on-reflection* true)

(def ^:private drain-delay-ms 120)
(def ^:private wait-limit-ms 2000)
(def ^:private poll-interval-ms 5)

(def ^:private observed-states
  "The state of the service each time the observed component halted."
  (atom []))

(defmethod ig/init-key ::observed
  [_ options]
  options)

(defmethod ig/halt-key! ::observed
  [_ {:keys [lifecycle]}]
  (swap! observed-states conj (lifecycle/state lifecycle)))

(defn- wait-for
  "Polls until a condition holds, and returns whether it did in time."
  [condition]
  (let [deadline (+ (System/currentTimeMillis) wait-limit-ms)]
    (loop []
      (cond
        (condition)
        true

        (> (System/currentTimeMillis) deadline)
        false

        :else
        (do (Thread/sleep (long poll-interval-ms))
            (recur))))))

(deftest lifecycle-component-test
  (let [system (ig/init {:borba/lifecycle {}})
        state  (:borba/lifecycle system)]
    (is (= :starting (lifecycle/state state)))
    (ig/halt! system)
    (is (lifecycle/stopped? state))))

(deftest core-component-test
  (testing "marks the service ready once it is up, and logs what it is"
    (let [system  (atom nil)
          state   (ig/ref :borba/lifecycle)
          core    {:service-name "payments"
                   :version      "1.2.3"
                   :profile      :prod
                   :lifecycle    state}
          entries (logging/call-capturing
                   #(reset! system (ig/init {:borba/lifecycle {}
                                             :borba/core      core})))]
      (is (= ["payments started (version 1.2.3, profile prod)"]
             (logging/messages entries)))
      (is (lifecycle/ready? (:borba/lifecycle @system)))))

  (testing "defaults the name and the version, and needs no lifecycle"
    (let [system  (ig/init {:borba/core {}})
          entries (logging/call-capturing #(ig/halt! system))]
      (is (= "borba-service is draining for 0 ms"
             (first (logging/messages entries)))))))

(deftest shutdown-test
  (testing "turns readiness off, waits, and only then goes on"
    (let [system  (ig/init {:borba/lifecycle {}
                            :borba/core
                            {:lifecycle      (ig/ref :borba/lifecycle)
                             :drain-delay-ms drain-delay-ms}})
          state   (:borba/lifecycle system)
          started (System/nanoTime)]
      (is (lifecycle/ready? state))
      (let [halting (future (ig/halt! system))]
        (is (wait-for #(lifecycle/draining? state)))
        (is (not (lifecycle/ready? state)))
        @halting)
      (is (lifecycle/stopped? state))
      (is (>= (/ (- (System/nanoTime) started) 1e6) drain-delay-ms)))))

(deftest ordering-test
  (testing "what the service comes after is still up while it drains"
    (reset! observed-states [])
    (let [state    (ig/ref :borba/lifecycle)
          observed (ig/ref ::observed)
          system   (ig/init {:borba/lifecycle {}
                             ::observed       {:lifecycle state}
                             :borba/core      {:lifecycle state
                                               :after     [observed]}})]
      (ig/halt! system)
      (is (= [:draining] @observed-states)))))
