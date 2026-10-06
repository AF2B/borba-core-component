(ns borba.core.lifecycle-test
  (:require
   [borba.core.lifecycle :as lifecycle]
   [clojure.test :refer [deftest is testing]]))

(defn- error-code
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(deftest states-test
  (let [state (lifecycle/create)]
    (is (= :starting (lifecycle/state state)))
    (is (not (lifecycle/ready? state)))

    (lifecycle/mark-ready! state)
    (is (lifecycle/ready? state))
    (is (not (lifecycle/draining? state)))

    (lifecycle/begin-drain! state)
    (is (lifecycle/draining? state))
    (is (not (lifecycle/ready? state)))

    (lifecycle/mark-stopped! state)
    (is (lifecycle/stopped? state))))

(deftest direction-test
  (testing "a service that fails to start goes straight to stopped"
    (is (lifecycle/stopped? (lifecycle/mark-stopped! (lifecycle/create)))))

  (testing "a move against the direction is refused, and says why"
    (let [state (lifecycle/mark-stopped! (lifecycle/create))]
      (is (= :borba.core.lifecycle/invalid-transition
             (error-code #(lifecycle/mark-ready! state))))
      (is (lifecycle/stopped? state))))

  (testing "a service cannot be drained before it was ready"
    (is (= :borba.core.lifecycle/invalid-transition
           (error-code #(lifecycle/begin-drain! (lifecycle/create)))))))

(deftest idempotence-test
  (testing "asking for a state the service is already in does nothing"
    (let [state (lifecycle/mark-ready! (lifecycle/create))]
      (lifecycle/mark-ready! state)
      (is (lifecycle/ready? state))
      (lifecycle/begin-drain! state)
      (lifecycle/begin-drain! state)
      (is (lifecycle/draining? state)))))
