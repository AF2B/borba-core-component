(ns borba.core.config
  "Reads the configuration of a service: an EDN file read with Aero, so that
   one file holds every profile and takes its secrets from the environment.

     {:ig/system
      #profile {:dev  {:server/http {:port 8080}}
                :prod #deep-merge [#include \"base.edn\"
                                   {:server/http {:port #env PORT}}]}}

   The tags are Aero's (#env, #or, #profile, #include, ...), plus
   `#deep-merge`, which merges maps recursively, and the readers of Integrant
   (#ig/ref, #ig/refset), which resources/data_readers.clj of this library
   registers."
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   ;; The readers of data_readers.clj name functions of Integrant, which have
   ;; to be loaded before a configuration that uses them is read.
   [integrant.core]))

(set! *warn-on-reflection* true)

(def ^:private config-resource "config.edn")

(defn deep-merge
  "Merges maps recursively: where two values are maps they are merged, and
   where they are not, the later one wins. A nil map is skipped.
   - maps: the maps to merge, from the base to the one that overrides"
  [& maps]
  (letfn [(merge-two [base
                      override]
            (if (and (map? base) (map? override))
              (merge-with merge-two base override)
              override))]
    (reduce merge-two {} (remove nil? maps))))

(defmethod aero/reader 'deep-merge
  [_options _tag values]
  (apply deep-merge values))

(defn read-config
  "Reads a configuration for a profile.
   - source: where to read it from, anything Aero reads: a resource URL, a
     file, a path or a reader
   - profile: the profile, a keyword such as :dev, :stag or :prod"
  [source
   profile]
  (when-not (keyword? profile)
    (throw (ex-info "the profile must be a keyword"
                    {:error   ::invalid-profile
                     :profile profile})))
  (when (nil? source)
    (throw (ex-info "there is no configuration to read"
                    {:error ::config-not-found})))
  (aero/read-config source {:profile profile}))

(defn load-config
  "Reads config.edn from the classpath for a profile.
   - profile: the profile, a keyword such as :dev, :stag or :prod"
  [profile]
  (read-config (io/resource config-resource) profile))
