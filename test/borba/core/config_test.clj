(ns borba.core.config-test
  (:require
   [borba.core.config :as config]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

(defn- temp-config
  "Writes a configuration to a temporary file and returns the file."
  [text]
  (let [file (File/createTempFile "borba-config" ".edn")]
    (.deleteOnExit file)
    (spit file text)
    file))

(deftest deep-merge-test
  (testing "merges nested maps and lets the later value win elsewhere"
    (is (= {:a {:b 1 :c 3 :d 4} :e 5}
           (config/deep-merge {:a {:b 1 :c 2}}
                              {:a {:c 3 :d 4} :e 5}))))

  (testing "a later non-map replaces an earlier map, and the reverse"
    (is (= {:a 1} (config/deep-merge {:a {:b 1}} {:a 1})))
    (is (= {:a {:b 1}} (config/deep-merge {:a 1} {:a {:b 1}}))))

  (testing "vectors are replaced, not concatenated"
    (is (= {:a [3]} (config/deep-merge {:a [1 2]} {:a [3]}))))

  (testing "a nil value overrides, as it says"
    (is (= {:a nil} (config/deep-merge {:a 1} {:a nil}))))

  (testing "a nil map is skipped, and no maps give an empty one"
    (is (= {:a 1} (config/deep-merge nil {:a 1} nil)))
    (is (= {} (config/deep-merge)))))

(deftest read-config-test
  (let [file (temp-config
              "{:name #profile {:dev \"development\" :prod \"production\"}
                :port #or [#env BORBA_TEST_UNSET_PORT 8080]
                :merged #deep-merge [{:a {:b 1 :c 2}} {:a {:c 3}}]
                :refs {:app/db {} :app/web {:db #ig/ref :app/db}}}")]
    (testing "selects what the profile names"
      (is (= "development" (:name (config/read-config file :dev))))
      (is (= "production" (:name (config/read-config file :prod)))))

    (testing "reads the environment, with a default for what is not set"
      (is (= 8080 (:port (config/read-config file :dev)))))

    (testing "merges maps with #deep-merge"
      (is (= {:a {:b 1 :c 3}} (:merged (config/read-config file :dev)))))

    (testing "reads the references of Integrant"
      (let [web (get-in (config/read-config file :dev) [:refs :app/web])]
        (is (ig/ref? (:db web)))))))

(deftest read-config-errors-test
  (testing "a profile must be a keyword"
    (let [thrown (try (config/read-config (temp-config "{}") "dev")
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :borba.core.config/invalid-profile (:error (ex-data thrown))))))

  (testing "there must be something to read"
    (let [thrown (try (config/read-config nil :dev)
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :borba.core.config/config-not-found (:error (ex-data thrown)))))))

(deftest load-config-test
  (testing "reads config.edn from the classpath"
    (let [loaded (config/load-config :test)]
      (is (= ['borba.core.sample] (:service/namespaces loaded)))
      (is (contains? (:ig/system loaded) :borba/core)))))

(def ^:private java-binary
  (str (System/getProperty "java.home") "/bin/java"))

(def ^:private reading-script
  "Reads a configuration with a reference, having loaded nothing but the
   namespace that reads it."
  (str "(require 'borba.core.config)"
       "(print (pr-str (:a (borba.core.config/read-config"
       "                   (java.io.StringReader. \"{:a #ig/ref :app/b}\")"
       "                   :dev))))"))

(defn- run-in-fresh-jvm
  "Runs a script in a JVM of its own, on the classpath of this one, and returns
   its exit status and what it printed.
   - script: the Clojure code to evaluate"
  [script]
  (let [command [java-binary
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main"
                 "-e" script]
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream true)))
        output  (slurp (.getInputStream process))]
    {:exit   (.waitFor process)
     :output output}))

(deftest standalone-reading-test
  (testing "reads references with nothing loaded beforehand, which only a
            fresh JVM can show"
    (is (= {:exit 0 :output "#integrant.core.Ref{:key :app/b}"}
           (run-in-fresh-jvm reading-script)))))
