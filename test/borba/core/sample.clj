(ns borba.core.sample
  "Components the tests start, which record what happened to them."
  (:require
   [integrant.core :as ig]))

(def events
  "What happened to the components, in order."
  (atom []))

(defn record!
  "Remembers that something happened to a component.
   - event: what happened"
  [event]
  (swap! events conj event))

(defmethod ig/init-key ::alpha
  [_ _options]
  (record! :alpha-started)
  :alpha)

(defmethod ig/halt-key! ::alpha
  [_ _alpha]
  (record! :alpha-halted))

(defmethod ig/init-key ::beta
  [_ {:keys [alpha]}]
  (record! [:beta-started alpha])
  :beta)

(defmethod ig/halt-key! ::beta
  [_ _beta]
  (record! :beta-halted))

(defmethod ig/init-key ::boom
  [_ _options]
  (throw (ex-info "this component never starts" {})))
