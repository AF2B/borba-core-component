(ns borba.core.main-test
  (:require
   [borba.core.logging :as logging]
   [borba.core.main :as main]
   [borba.core.system :as system]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.io File)))

(use-fixtures :each
  (fn [run-test]
    (system/stop!)
    (run-test)
    (system/stop!)))

(set! *warn-on-reflection* true)

(deftest resolve-profile-test
  (testing "the first argument wins"
    (is (= :prod (main/resolve-profile ["prod"] {"PROFILE" "dev"}))))

  (testing "the environment is the fallback"
    (is (= :dev (main/resolve-profile [] {"PROFILE" "dev"}))))

  (testing "there is none to guess"
    (is (nil? (main/resolve-profile [] {})))
    (is (nil? (main/resolve-profile [""] {})))
    (is (nil? (main/resolve-profile nil {"PROFILE" ""})))))

(deftest run-test
  (testing "starts the system of the configuration of a profile"
    (let [system (main/run {:profile :test :hook? false})]
      (is (identical? system (system/current)))
      (is (contains? system :borba/core))
      (system/stop!)))

  (testing "reads another source when it is given one"
    (let [file (File/createTempFile "borba-main" ".edn")]
      (.deleteOnExit file)
      (spit file "{:ig/system {:borba/lifecycle {}}}")
      (is (= [:borba/lifecycle]
             (keys (main/run {:profile :dev :source file :hook? false})))))))

(deftest shutdown-hook-test
  (testing "stops the running system when it is run"
    (main/run {:profile :test :hook? false})
    (let [^Thread hook (main/shutdown-hook)]
      (is (= "borba-shutdown" (.getName hook)))
      (.run hook)
      (is (nil? (system/current)))))

  (testing "does nothing when no system is running"
    (let [^Thread hook (main/shutdown-hook)]
      (is (nil? (.run hook))))))

(deftest start-test
  (testing "no profile is a usage error, and says so"
    (let [entries (logging/call-capturing #(main/start [] {}))]
      (is (= {:exit 2} (main/start [] {})))
      (is (re-find #"no profile" (first (logging/messages entries))))))

  (testing "a configuration that cannot start is a logged failure"
    (let [result  (atom nil)
          entries (logging/call-capturing
                   #(reset! result (main/start ["broken"] {})))]
      (is (= {:exit 1} @result))
      (is (= "the service failed to start" (:message (first entries))))
      (is (instance? Throwable (:cause (first entries)))))))
