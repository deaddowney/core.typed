(ns ^:skip-wiki
  ^{:core.typed {:collect-only true}}
  clojure.core.typed.tvar-env
  (:require [clojure.core.typed.utils :as u]
            [clojure.core.typed.type-rep :as r]
            [clojure.core.typed :as t :refer [fn>]])
  (:import (clojure.core.typed.type_rep F)
           (clojure.lang Symbol)))

(alter-meta! *ns* assoc :skip-wiki true
             :core.typed {:collect-only true})

;; this implements the Delta environment from the TOPLAS paper
;; (as well as every other paper on System F)

;; this environment maps type variables names (symbols)
;; to types representing the type variable
;;
;; The mapped-to type is used to distinguish type variables bound
;; at different scopes

(t/def-alias TVarEnv
  "Map from scoped symbols to the actual free
  variables they represent"
  (t/Map Symbol F))

(t/ann ^:no-check tvar-env? (predicate TVarEnv))
(def tvar-env? (u/hash-c? symbol? r/F?))

(t/ann initial-tvar-env TVarEnv)
(def initial-tvar-env {})

(t/ann ^:no-check *current-tvars* TVarEnv)
(defonce ^:dynamic *current-tvars* initial-tvar-env)
(t/tc-ignore
(set-validator! #'*current-tvars* tvar-env?)
  )

(defmacro with-extended-tvars
  "Takes a list of vars and extends the current tvar environment."
  [vars & body]
  `(binding [*current-tvars* (extend-many *current-tvars* ~vars)]
     ~@body))

(defmacro with-extended-new-tvars
  "Extends with new type variables (provided by (e.g., Poly-fresh))"
  [vars fresh-vars & body]
  `(binding [*current-tvars* (extend-many *current-tvars* ~vars ~fresh-vars)]
     ~@body))

(t/ann bound-tvar? [Symbol -> Boolean])
(defn bound-tvar?
  "Returns true if the current type variable is bound"
  [var]
  (contains? *current-tvars* var))

(t/ann lookup-tvar [Symbol -> (t/Nilable r/Type)])
(defn lookup-tvar
  "Returns the mapped-to type, or nil"
  [var]
  (*current-tvars* var))

(t/ann extend-one (Fn [TVarEnv Symbol -> TVarEnv]
                      [TVarEnv Symbol (t/Nilable Symbol) -> TVarEnv]))
(defn extend-one
  "Extend a tvar environment. Adds an entry mapping var to itself,
  or if fresh-var is provided, mapping var to fresh-var"
  ([env var] (extend-one env var nil))
  ([env var fresh-var]
   {:pre [(tvar-env? env)
          (symbol? var)
          ((some-fn symbol? nil?) fresh-var)]
    :post [(tvar-env? %)]}
   (assoc env var (r/make-F (or fresh-var var)))))

(t/ann extend-many [TVarEnv (t/Coll Symbol) (t/Nilable (t/Coll Symbol)) -> TVarEnv])
(defn extend-many
  "Extends env with vars. If fresh-vars are provided, the vars will map to them
  pairwise in the resulting environment."
  ([env vars] (extend-many env vars nil))
  ([env vars fresh-vars]
   {:post [(tvar-env? %)]}
   (let [fresh-vars (or fresh-vars (repeat (count vars) nil))
         _ (assert (= (count vars) (count fresh-vars)))]
     (reduce (fn [env [var fresh-var]]
               {:pre [(symbol? var)
                      ((some-fn nil? symbol?) fresh-var)]}
               (extend-one env var fresh-var))
             env
             (map vector vars fresh-vars)))))
