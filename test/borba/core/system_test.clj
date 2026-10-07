(ns borba.core.system-test
  (:require
   [borba.core.logging :as logging]
   [borba.core.sample :as sample]
   [borba.core.system :as system]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [integrant.core :as ig]))

(use-fixtures :each
  (fn [run-test]
    (reset! sample/events [])
    (system/stop!)
    (run-test)
    (system/stop!)))

(def ^:private alpha (ig/ref :borba.core.sample/alpha))

(def ^:private config
  {:ig/system {:borba.core.sample/alpha {}
               :borba.core.sample/beta  {:alpha alpha}}})

(defn- error-code
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(deftest init-and-halt-test
  (testing "starts the components after what they refer to, and halts them in
            the reverse order"
    (let [system (system/init config)]
      (is (= [:alpha-started [:beta-started :alpha]] @sample/events))
      (system/halt system)
      (is (= [:alpha-started [:beta-started :alpha] :beta-halted :alpha-halted]
             @sample/events)))))

(deftest init-failure-test
  (testing "a configuration with no system is refused"
    (is (= :borba.core.system/no-system
           (error-code #(system/init {})))))

  (testing "a component that fails to start does not leave the others running"
    (let [failing {:ig/system {:borba.core.sample/alpha {}
                               :borba.core.sample/boom  {:alpha alpha}}}]
      (is (thrown? clojure.lang.ExceptionInfo (system/init failing)))
      (is (= [:alpha-started :alpha-halted] @sample/events)))))

(deftest unregistered-component-test
  (testing "a key that no namespace registered says so, and which key it is"
    (let [thrown (try (system/init {:ig/system {:nowhere.at.all/thing {}}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:error     :borba.core.system/unregistered-component
              :key       :nowhere.at.all/thing
              :namespace nil}
             (ex-data thrown)))
      (is (str/includes? (ex-message thrown) ":nowhere.at.all/thing"))
      (is (str/includes? (ex-message thrown) ":service/namespaces"))
      (is (some? (ex-cause thrown)))))

  (testing "a namespace that is loaded and has no such function says so too"
    (is (= :borba.core.system/unregistered-component
           (error-code #(system/init
                         {:ig/system {:clojure.string/nothing-of-this-name
                                      {}}})))))

  (testing "names the namespace of a component of the Borba libraries"
    (let [thrown (try (system/init {:ig/system {:server/http {}}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= 'borba.server.component (:namespace (ex-data thrown))))
      (is (str/includes? (ex-message thrown) "borba.server.component"))))

  (testing "does not change the failure of a component that is registered"
    (let [thrown (try (system/init {:ig/system {:borba.core.sample/boom {}}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (nil? (:error (ex-data thrown))))
      (is (= :integrant.core/build-threw-exception
             (:reason (ex-data thrown))))))

  (testing "halts what had started before it fails"
    (is (= :borba.core.system/unregistered-component
           (error-code #(system/init
                         {:ig/system {:borba.core.sample/alpha {}
                                      :nowhere.at.all/thing
                                      {:alpha alpha}}}))))
    (is (some #{:alpha-halted} @sample/events))))

(deftest namespaces-test
  (testing "loads the namespaces a configuration lists"
    (is (nil? (system/require-namespaces ['clojure.set 'clojure.string]))))

  (testing "names the namespace that cannot be loaded"
    (let [thrown (try (system/require-namespaces ['clojure.set 'no.such.ns])
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :borba.core.system/namespace-not-found (:error (ex-data thrown))))
      (is (= 'no.such.ns (:namespace (ex-data thrown))))
      (is (instance? java.io.FileNotFoundException (ex-cause thrown))))))

(deftest running-system-test
  (testing "start! keeps the system, and stop! halts it once"
    (is (nil? (system/current)))
    (let [system (system/start! config)]
      (is (identical? system (system/current)))
      (system/stop!)
      (is (nil? (system/current)))
      (system/stop!)
      (is (= 2 (count (filter #{:alpha-halted :beta-halted} @sample/events))))))

  (testing "a process runs one system"
    (system/start! config)
    (is (= :borba.core.system/already-running
           (error-code #(system/start! config))))))

(deftest logging-test
  (testing "says when the system started and stopped"
    (let [entries (logging/call-capturing
                   (fn []
                     (system/start! config)
                     (system/stop!)))]
      (is (= ["system started, components: 2" "system stopped"]
             (logging/messages entries))))))
