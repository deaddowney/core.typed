(ns typed.core
  (:import (clojure.lang Var Symbol IPersistentList IPersistentVector Keyword Cons
                         Ratio Atom IPersistentMap Seqable Counted ILookup ISeq
                         IMeta IObj Associative))
  (:require [trammel.core :refer [defconstrainedrecord defconstrainedvar
                                  constrained-atom]]
            [analyze.core :as a :refer [analyze-path ast]]
            [analyze.util :as util]
            [clojure.set :as set]))
(prn "RELOAD CORE")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Annotation

(defmacro +T 
  "Annotate a top level identifier. Takes
  a symbol and a type. If the symbol is unqualified,
  it is implicitly qualified in the current namespace."
  [nme type-syn]
  `(*add-type-ann-fn* 
     ~(if (namespace nme)
        `'~nme
        `(symbol (-> *ns* ns-name name) (name '~nme)))
     '~type-syn))

(def unevaled-typedcore-anns
  "Unevaluated annotations for typed.core namespace"
  (atom []))

(declare parse-dom)

(def ^:dynamic 
  *add-type-ann-fn* 
  (fn [sym type-syn]
    (when (= *ns* (find-ns 'typed.core))
      (swap! unevaled-typedcore-anns 
             (fn [^{:- Seqable} a]
               (conj a
                     [(if (namespace sym)
                        sym 
                        (symbol "typed.core" (name sym)))
                      type-syn]))))
    [sym :- type-syn]))
(+T *add-type-ann-fn* [Symbol IParseType -> nil])
(+T unevaled-typedcore-anns Atom)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Typed require

(+T ns-deps-contract [(Mapof [Any Any]) -> Boolean])
(defn ns-deps-contract [m]
  (and (every? symbol? (keys m))
       (every? set? (vals m))
       (every? (fn [^{+T (Seqof Any)} vs] 
                 (every? symbol? vs)) 
               (vals m))))

(+T ns-deps Atom)
(def ns-deps (constrained-atom {}
                               "Map from symbols to seqs of symbols"
                               [ns-deps-contract]))

(+T add-ns-dep [Symbol Symbol -> nil])
(defn add-ns-dep [nsym ns-dep]
  (swap! ns-deps update-in [nsym] #(set/union % #{ns-dep}))
  nil)

(defmacro require-typed [nsym]
  `(add-ns-dep (symbol (-> *ns* ns-name name))
               '~nsym))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Debug macros

(+T debug-mode Atom)
(def debug-mode (atom true))

(+T print-warnings Atom)
(def print-warnings (atom false))

(defmacro warn [& body]
  `(when @print-warnings
     (println ~@body)))

(defmacro debug [& body]
  `(when @debug-mode
     (println ~@body)))

(defmacro log [& body]
  `(when @debug-mode
     (println ~@body)))

(defmacro tc-form [frm]
  `(-> (ast ~frm) tc-expr))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Top levels

(declare add-type-ann parse ^:dynamic *type-db* tc-expr unparse)

(def ^:dynamic *already-reloaded*)

(defn reload-ns [nsym]
  (prn "reload" nsym)
  (prn "already reloaded" @*already-reloaded*)
  (when (not (contains? @*already-reloaded* nsym))
    (require :reload nsym)
    (swap! *already-reloaded* set/union #{nsym})))

(defn require-typed-deps [nsym]
  (doseq [depsym (@ns-deps nsym)]
    (reload-ns depsym)
    (require-typed-deps depsym)))

(+T check-namespace [Symbol -> nil])
(defn check-namespace [nsym]
  (let [ 
        ;; 1. Collect all type annotations
        _ (binding [*add-type-ann-fn* (fn [sym type-syn]
                                        (debug "add type:" sym :- type-syn)
                                        (add-type-ann sym (parse type-syn)))
                    *already-reloaded* (atom #{'typed.core})]
            (reload-ns 'typed.base)
            (reload-ns nsym)
            (require-typed-deps nsym)
            (doseq [[sym syntax] @unevaled-typedcore-anns]
              (prn "add typed.core type" sym)
              (add-type-ann sym (parse syntax))))
        
        ;; 2. Perform type checking
        asts (analyze-path nsym)
        
        _ (doseq [a asts]
            (try (tc-expr a)
              (catch Throwable e
                (throw e))))]
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type hierarchy

(+T type-key Keyword)
(def type-key ::+T)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type variable Scope

(declare Type? TypeVariable?)

(def tvar-scope ::tvar-scope)
(defn make-tvar-binding 
  "Make Type t a binding position for variables s"
  [t s]
  (assert (Type? t))
  (assert (every? TypeVariable? s))
  (with-meta t {tvar-scope s}))

(defn tvar-binding
  "If Type t is a binding position, return the variables binded there"
  [t]
  (assert (Type? t) (class t))
  (-> t meta tvar-scope))

(defn update-tvar-binding 
  "Use function f to update the binding position t"
  [t f]
  (vary-meta t #(update-in % [tvar-scope] f)))

(defn remove-tvar-binding
  "Return Type t without a binding position"
  [t]
  (vary-meta t #(dissoc % tvar-scope)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utils

(+T class-satisfies-protocol? [IPersistentMap Class -> Boolean])
(defn class-satisfies-protocol?
  "Returns the method that would be dispatched by applying
  an instance of Class c to protocol"
  [protocol c]
  (boolean
    (if (isa? c (:on-interface protocol))
      c
      (let [impl #(get (:impls protocol) %)]
        (or (impl c)
            (and c (or (first (remove nil? (map impl (butlast (@#'clojure.core/super-chain c)))))
                       (when-let [t (@#'clojure.core/reduce1 @#'clojure.core/pref (filter impl (disj (supers c) Object)))]
                         (impl t))
                       (impl Object))))))))

(declare PrimitiveClass-from ClassType-from Type Type? ->ProtocolType
         ->QualifiedKeyword var-or-class->sym)

(+T primitives (Mapof [Symbol Class]))
(def ^:private primitive-symbol
  {'char Character/TYPE
   'boolean Boolean/TYPE
   'byte Byte/TYPE
   'short Short/TYPE
   'int Integer/TYPE
   'long Long/TYPE
   'float Float/TYPE
   'double Double/TYPE
   'void Void/TYPE})

(declare ^:dynamic *type-var-scope*)

(+T resolve-symbol [Symbol -> Type])
(defn- resolve-symbol [sym]
  (assert (symbol? sym))
  (cond
    (primitive-symbol sym)
    (PrimitiveClass-from sym)

    (*type-var-scope* sym)
    (*type-var-scope* sym)

    :else
    (let [res (resolve sym)]
      (cond
        (class? res) (if (.isPrimitive res)
                       (PrimitiveClass-from res)
                       (ClassType-from res))

        (var? res) (let [v @res]
                     (cond
                       (Type? v) v 

                       (map? v) (->ProtocolType (var-or-class->sym res))

                       (and (keyword? v)
                            (namespace v))
                       (->QualifiedKeyword v)
                       
                       :else (throw (Exception. (str "Not a type: " sym)))))

        :else (throw (Exception. (str "Could not resolve " sym)))))))

(declare map->Fun map->arity union Nil PrimitiveClass? subtype?)

(+T method->Fun [clojure.reflect.Method -> Fun])
(defn- method->Fun [method]
  (try
    (map->Fun
      {:arities #{(map->arity 
                    {:dom (->> 
                            (map resolve-symbol (:parameter-types method))
                            (map #(if (or (PrimitiveClass? %)
                                          (subtype? Nil %))
                                    %                   ; null cannot substutitute for JVM primtiives
                                    (union [Nil %])))) ; Java Objects can be the null reference
                     :rng (let [typ (resolve-symbol (:return-type method))]
                            (if (or (PrimitiveClass? typ)
                                    (subtype? Nil typ))
                              typ                        ; null cannot substutitute for JVM primtiives
                              (union [Nil typ])))})}}) ; Java Objects can be the null reference
    (catch Throwable e
      (throw (ex-info "Could not create Fun from Method" {:method method} e)))))


(+T var-or-class->sym [(U Var Class) -> Symbol])
(defn var-or-class->sym [var-or-class]
  {:pre [(or (var? var-or-class)
             (class? var-or-class))]}
  (cond
    (var? var-or-class) (symbol (str (.name (.ns var-or-class))) (str (.sym var-or-class)))
    :else (symbol (.getName var-or-class))))

(defmacro map-all-true? [& body]
  `(every? true? (map ~@body)))

(declare subtype? unparse-type)

(+T unp [Type -> String])
(defn unp
  "Unparse a type and return string representation"
  [t]
  (with-out-str (-> t unparse-type pr)))

(+T assert-subtype [Type Type & Any * -> nil])
(defn assert-subtype [actual-type expected-type & msgs]
  (assert (subtype? actual-type expected-type)
          (apply str "Expected " (unp expected-type) ", found " (unp actual-type)
                 msgs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type contexts

(declare Type?)

(+T type-db-var-contract [IPersistentMap -> Boolean])
(defn type-db-var-contract [m]
  (and (every? namespace (keys @m))
       (every? Type? (vals @m))))

(+T type-db-atom-contract [IPersistentMap -> Boolean])
(defn type-db-atom-contract [m]
  (and (every? namespace (keys m))
       (every? Type? (vals m))))

(+T *type-db* (Mapof [Symbol Type]))
(def ^:dynamic *type-db* 
  (constrained-atom {}
                    "Map from qualified symbols to types"
                    [type-db-atom-contract]))

(+T local-type-db-contract [IPersistentMap -> Boolean])
(defn local-type-db-contract [m]
  (and (every? (complement namespace) (keys m))
       (every? Type? (vals m))))

(+T *local-type-db* (Mapof [Symbol Type]))
(defconstrainedvar 
  ^:dynamic *local-type-db* {}
  "Map from unqualified names to types"
  [local-type-db-contract])

(+T type-var-scope-contract [IPersistentMap -> Boolean])
(defn type-var-scope-contract [m]
  (and (every? (every-pred symbol? (complement namespace)) 
               (keys m))
       (every? Type? (vals m))))

(+T *type-var-scope* (Mapof [Symbol TypeVariable]))
(defconstrainedvar
  ^:dynamic *type-var-scope* {}
  "Map from unqualified names to types"
  [type-var-scope-contract])

(+T reset-type-db [-> nil])
(defn reset-type-db []
  (swap! *type-db* (constantly {}))
  nil)

(+T type-of [(U Symbol Var) -> Type])
(defn type-of [sym-or-var]
  {:pre [(or (symbol? sym-or-var)
             (var? sym-or-var))]
   :post [(Type? %)]}
  (let [sym (if (var? sym-or-var)
              (symbol (str (.name (.ns sym-or-var))) (str (.sym sym-or-var)))
              sym-or-var)]
    (if-let [the-local-type (and (not (namespace sym))
                                 (*local-type-db* sym))]
      the-local-type
      (if-let [the-type (and (namespace sym)
                             (@*type-db* sym))]
        the-type
        (throw (Exception. (str "No type for " sym)))))))

(declare ->ParameterisedType ParameterisedType?)

(defn typed-classes-var-contract [a]
  (and (map? @a)
       (every? symbol? (keys @a))
       (every? #(class? (resolve %)) (keys @a))
       (every? ParameterisedType? (vals @a))))

(defn typed-classes-atom-contract [m]
  (and (map? m)
       (every? symbol? (keys m))
       (every? #(class? (resolve %)) (keys m))
       (every? ParameterisedType? (vals m))))

(defconstrainedvar 
  ^:dynamic *typed-classes* 
  (constrained-atom {}
                    "A map of qualified symbols to ParameterisedType's"
                    [typed-classes-atom-contract])
  "Atom containing a map of qualified symbols to ParameterisedType's"
  [typed-classes-var-contract])

(defn add-typed-class [cls fields opts]
    (assert (class? cls))
    (assert (every? TypeVariable? fields))
  (let [csym (symbol (.getName cls))
        extends (:extends opts)]
    (swap! *typed-classes* #(assoc %
                                   csym
                                   (->ParameterisedType csym fields)))))

(defmacro with-type-vars [var-map & body]
  `(binding [*type-var-scope* (merge *type-var-scope* ~var-map)]
     ~@body))

(defmacro with-local-types [type-map & body]
  `(binding [*local-type-db* (merge *local-type-db* ~type-map)]
     ~@body))

(+T add-type-ann [Symbol Type -> (Vector* Symbol Any)])
(defn add-type-ann [sym typ]
  (when-let [oldtyp (@*type-db* sym)]
    (when (not= oldtyp typ)
      (warn "Overwriting type for" sym ":" typ "from" (unparse oldtyp))))
  (swap! *type-db* assoc sym typ)
  [sym :- (unparse typ)])

(defmacro with-type-anns [type-map-syn & body]
  `(binding [*type-db* (constrained-atom (into {} 
                                               (doall (map #(vector (or (when-let [var-or-class# (resolve (first %))]
                                                                          (var-or-class->sym var-or-class#))
                                                                        (when (namespace (first %))
                                                                          (first %))
                                                                        (symbol (str (ns-name *ns*)) (name (first %))))
                                                                    (parse-syntax (second %)))
                                                           '~type-map-syn)))
                                         "Map from qualified symbols to types"
                                         [type-db-atom-contract])]
     ~@body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Typed Classes

(defmacro annotate-class [nme fields & opts]
  `(let [r# (resolve '~nme)]
       (assert (class? r#) r#)
     (add-typed-class r# (map -tv '~fields) (apply hash-map '~opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Typed Protocol

(comment
  (def-typed-protocol IPro
    (myfn [this (at :- Double)] :- Number
          [this (blah :- Object) & (blahs :- Any *)] :- Long
          "Blah"))

  (def-typed-record A
    IPro
    (myfn 
      ([this (at :- Double)] :- Number
       (+ 1 at))
      ([this (blah :- Object) & (blahs :- Any *)] :- Long
       (when (every? number? blahs)
         (apply + blahs)))))
    )


;(defn- convert-protocol-arity [[dom-syn rng-syn]]
;  (let [doms (map last (rest dom-syn))
;        doms ]
;
;(defn- build-protocol-method [[nme & ms]]
;  (let [doc (when (string? (last ms))
;              (last ms))
;        methods (apply hash-map
;                       (remove #(= :- %)
;                               (if (string? (last m))
;                                 (butlast m)
;                                 m)))]
;    (list* nme (concat (map convert-protocol-arity methods)
;                       (when doc
;                         [doc])))))
;
;
;(defmacro def-typed-protocol [name]
;  `(defprotocol ~name


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types

(+T Type Keyword)
(def Type ::type-type)

(+T Type? [Any -> Boolean])
(defn Type? [t]
  (isa? (class t) Type))

(defprotocol IFreeVars
  (-free-vars [this]))

(defn free-vars [t]
  (-free-vars t))

(defmacro def-type [nme & body]
  `(let [a# (defconstrainedrecord ~nme ~@body)]
     (derive a# Type)
     a#))

(def-type Value [val]
  "A singleton type for values, except nil"
  {:pre [(not (nil? val))]}
  
  IFreeVars
  (-free-vars [this] nil))

(declare subtype?)

(def-type Union [types]
  "A disjoint union of types"
  {:pre [(every? Type? types)
         (every? 
           (fn [t]
             (every? #(not (subtype? % t))
                     (disj (set types) t)))
           types)]}
  
  IFreeVars
  (-free-vars [this] 
    (mapcat free-vars types)))

(def-type NilType []
  "The nil type"
  []
  
  IFreeVars
  (-free-vars [this] nil))

(def Nil (->NilType))
(def Nil? (partial = Nil))

(def-type ClassType [the-class]
  "A class"
  {:pre [(symbol? the-class)
         (let [c (resolve the-class)]
           (and (class? c)
                (not (.isPrimitive c))))]}

  IFreeVars
  (-free-vars [this] nil))

(+T ClassType-from [Class -> ClassType])
(defn ClassType-from [cls]
  (assert (class? cls))
  (->ClassType (symbol (.getName cls))))

(def-type ParameterisedType [class-sym fields]
  "A type for parameterised classes. Takes a symbol
  representing the class it is parameterising, and a list
  of fields, type variables"
  {:pre [(symbol? class-sym)
         (class? (resolve class-sym))
         (every? TypeVariable? fields)]})

;TODO primitives?
(def Any (Union. #{Nil (ClassType-from Object)})) ; avoid constrained constructor because of
                                                  ; call to subtype?, which is undefined
(def Any? (partial = Any))

(def Nothing (Union. #{}))
(def Nothing? (partial = Nothing))

(def True (->Value true))
(def True? (partial = True))

(def False (->Value false))
(def False? (partial = False))

(def falsy-values #{False Nil})

;keyword

(def-type QualifiedKeyword [kwrd]
  "A fully qualified keyword"
  [(keyword? kwrd)
   (namespace kwrd)]

  IFreeVars
  (-free-vars [this] nil))

;; Base types

(declare arity?)

(def-type Fun [arities]
  "Function with one or more arities"
  {:pre [(set? arities)
         (seq arities)
         (every? arity? arities)]}

  IFreeVars
  (-free-vars [this] 
    (mapcat free-vars arities)))

(defn -fun [arities]
  (->Fun (set arities)))

(def-type PrimitiveClass [the-class]
  "A primitive class"
  {:pre [(symbol? the-class)
         (let [c (primitive-symbol the-class)]
           (and (class? c)
                (.isPrimitive c)))]}

  IFreeVars
  (-free-vars [this] nil))

(+T PrimitiveClass-from [Symbol -> PrimitiveClass])
(defn PrimitiveClass-from 
  "Create a PrimitiveClass from a symbol representing a
  primitive class name (int, long etc.) or a primitive Class
  object"
  [sym]
  (->PrimitiveClass
    (symbol (.getName (or (primitive-symbol sym)
                          (resolve-symbol sym))))))

(def-type ProtocolType [the-protocol-var]
  "A protocol"
  {:pre [(symbol? the-protocol-var)
         (namespace the-protocol-var)]}

  IFreeVars
  (-free-vars [this] nil))

(defn- simplify-union [the-union]
  (cond 
    (some #(Union? %) (:types the-union))
    (recur (->Union (set (doall (mapcat #(or (and (Union? %)
                                               (:types %))
                                             [%])
                                        (:types the-union))))))

    (= 1
       (count (:types the-union)))
    (first (:types the-union))
    
    :else the-union))

(defn union [types]
  (simplify-union (->Union (set types))))

(def-type Intersection [types]
  "An intersection of types"
  {:pre [(every? Type? types)]}

  IFreeVars
  (-free-vars [this] 
    (mapcat free-vars types)))

;; type variables

(def-type TypeVariable [nme bnd]
  "A record for bounded type variables, with an unqualified symbol as a name"
  {:pre [(symbol? nme)
         (not (primitive-symbol nme))
         (not (namespace nme))
         (Type? bnd)
         (not (TypeVariable? bnd))]}

  IFreeVars
  (-free-vars [this]
    (concat [this] (free-vars bnd))))

(defn -tv 
  "Create a type variable with an optional bound"
  ([nme] (->TypeVariable nme Any))
  ([nme bnd] (->TypeVariable nme bnd)))

(def type-variables #{TypeVariable})

(defn type-variable? [t]
  (boolean (type-variables (class t))))

;; arities

(def Arity ::arity-type)

(defn Arity? [a]
  (isa? (class a) Arity))

(declare FilterSet?)

;; arity is NOT a type
(def-type arity [dom rng rest-type flter]
  "An arity with fixed or variable domain. Supports optional filter, and optional type parameters"
  {:pre [(every? Type? dom)
         (Type? rng)
         (or (nil? rest-type)
             (Type? rest-type))
         (or (nil? flter)
             (FilterSet? flter))]}

  IFreeVars
  (-free-vars [this]
    (mapcat free-vars 
            (concat dom
                    [rng]
                    (when rest-type
                      [rest-type])))))


(declare subtypes?)

(defn subtypes?*-varargs [argtys dom rst]
  (loop [dom dom
         argtys argtys]
    (cond
      (and (empty? argtys)
           (empty? dom))
      true

      (empty? argtys)
      false

      (and (empty? dom)
           rst)
      (if (subtype? (first argtys) rst)
        (recur dom (next argtys))
        false)

      (empty? dom)
      false

      (subtype? (first argtys)
                (first dom))
      (recur (next argtys)
             (next dom))

      :else false)))


(def top-arity ::top-arity)

(declare subtype?)

(defn subtype?*-arity [s t]
  (assert (not (:rest-type s)))
  (assert (not (:rest-type t)))
  (and (map-all-true? subtype? 
                      (:dom s)
                      (:dom t))
       (subtype? (:rng s)
                 (:rng t))))

(defn similar-arity 
  "Return a2 if a1 looks like it, using number of arguments"
  [a1 a2]
  (when (or (:rest-type a1)
            (:rest-type a2)
            (= (count (:dom a1))
               (count (:dom a2))))
    a2))


(defn match-to-fun-arity [s fun-type]
  (first 
    (filter #(= (count (:dom s))
                (count (:dom %)))
            (:arities fun-type))))


(defn matches-args [arr args]
  (when (or (and (:rest-type arr)
                 (<= (count (:dom arr))
                     (count args)))
            (= (count (:dom arr))
               (count args)))
    arr))

; data structures

(def-type Seq [type]
  "A seq of type type, subtype of clojure.lang.ISeq"
  {:pre [(Type? type)]}
  
  IFreeVars
  (-free-vars [this]
    (free-vars type)))

(def-type Vector [type]
  "A vector of type type, subtype of clojure.lang.IPersistentVector"
  {:pre [(Type? type)]}

  IFreeVars
  (-free-vars [this]
    (free-vars type)))

(def-type ConstantVector [types]
  "A constant vector type, subtype of clojure.lang.IPersistentVector"
  [(every? Type? types)]

  IFreeVars
  (-free-vars [this]
    (mapcat free-vars types)))

(def-type Sequential [type]
  "A sequential collection type, subtype of clojure.lang.Sequential"
  [(Type? type)]

  IFreeVars
  (-free-vars [this]
    (free-vars type)))

(def-type ConstantSequential [types]
  "A constant sequential collection type, subtype of clojure.lang.Sequential"
  [(every? Type? types)]

  IFreeVars
  (-free-vars [this]
    (mapcat free-vars types)))

(def-type Map [kvtype]
  "A sequential collection type, subtype of clojure.lang.IPersistentMap"
  [(= 2 (count kvtype))
   (Type? (first kvtype))
   (Type? (second kvtype))]

  IFreeVars
  (-free-vars [this]
    (mapcat free-vars kvtype)))

#_(def-type ConstantMap [kvtypes]
  "A constant sequential collection type, subtype of clojure.lang.IPersistentMap"
  [(every? #(and (Type? (first %))
                 (Type? (second %)))
           kvtypes)])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Filters

(def ^:private Filter ::filter-type)

(defmacro def-filter [nme & body]
  `(let [a# (defconstrainedrecord ~nme ~@body)]
     (derive a# Filter)
     a#))

(defn Filter? [a]
  (isa? (class a) Filter))

(def-filter TrivialFilter []
  "A proposition that is always true"
  [])

(def-filter ImpossibleFilter []
  "A proposition that is never true"
  [])

(def-filter TypeFilter [var type]
  "A proposition that says var is of type type"
  {:pre [(symbol? var)
         (Type? type)]})

(def-filter NotTypeFilter [var type]
  "A proposition that says var is not of type type"
  {:pre [(symbol? var)
         (Type? type)]})

(def-filter FilterSet [then else]
  "Contains two propositions, then for the truthy result,
  else for the falsy result"
  {:pre [(Filter? then)
         (Filter? else)]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parse Type syntax

(defprotocol IParseType
  ;(+T parse-syntax* [IParseType -> Type])
  (parse-syntax* [this]))

(declare Fun-literal All-literal)

(defn parse-syntax
  "Type syntax parser, entry point"
  {:post [Type?]}
  [syn]
  (cond
    ;Parse single arity function syntax
    (vector? syn)
    (parse-syntax (list Fun-literal syn))

    ;Parse (All ..) forms
    (and (list? syn)
         (= All-literal
            (first syn)))
    (let [[_ tvar-syn body-syn] syn
          ;; tvar-syn with optional bound (All [x (y <: Number)] ..)
          tvars (map #(if (symbol? %)
                        (-tv %)
                        (-tv (first %)
                             (parse-syntax (nth % 2))))
                     tvar-syn)
          scope (into {} (map #(vector (:nme %) %) tvars))]
      (with-type-vars scope
        (let [t (parse-syntax body-syn)]
          (update-tvar-binding t #(concat tvars %))))) ; handle nested scopes

    :else
    (parse-syntax* syn)))

(def parse parse-syntax)

(extend-protocol IParseType
  Symbol
  (parse-syntax* [this]
    (resolve-symbol this))
  
  Boolean
  (parse-syntax* [this]
    (if this
      True
      False))

  String
  (parse-syntax* [this]
    (->Value this))

  Character
  (parse-syntax* [this]
    (->Value this))

  Keyword
  (parse-syntax* [this]
    (if (namespace this)
      (->QualifiedKeyword this)
      (->Value this)))

  Double
  (parse-syntax* [this]
    (->Value this))

  java.math.BigDecimal
  (parse-syntax* [this]
    (->Value this))

  clojure.lang.Ratio
  (parse-syntax* [this]
    (->Value this))
  
  Long
  (parse-syntax* [this]
    (->Value this))
  
  nil
  (parse-syntax* [this]
    Nil))

(defmulti parse-list-syntax first)

(def All-literal 'All)
(def U-literal 'U)
(def I-literal 'I)
(def Fun-literal 'Fun)
(def predicate-literal 'predicate)
(def Vectorof-literal 'Vectorof)
(def Vector*-literal 'Vector*)
(def Sequentialof-literal 'Sequentialof)
(def Sequential*-literal 'Sequential*)
(def Seqof-literal 'Seqof)
(def Mapof-literal 'Mapof)
(def Map*-literal 'Map*)
        
(defmethod parse-list-syntax Vector*-literal
  [[_ & syns]]
  (->ConstantVector (doall (map parse syns))))
        
(defmethod parse-list-syntax Vectorof-literal
  [[_ syn]]
  (->Vector (parse syn)))
        
(defmethod parse-list-syntax Sequential*-literal
  [[_ & syns]]
  (->ConstantSequential (doall (map parse syns))))
        
(defmethod parse-list-syntax Sequentialof-literal
  [[_ syn]]
  (->Sequential (parse syn)))

(defmethod parse-list-syntax Seqof-literal
  [[_ syn]]
  (->Seq (parse syn)))
        
#_(defmethod parse-list-syntax Map*-literal
  [[_ & kvsyns]]
  (->ConstantMap (doall (map #(vector (parse (first %))
                                      (parse (second %)))
                             kvsyns))))

(defmethod parse-list-syntax Mapof-literal
  [[_ kvsyn]]
  (let [_ (assert (vector? kvsyn) "Mapof takes a vector map-entry")
        [ksyn vsyn] kvsyn]
    (->Map [(parse ksyn) (parse vsyn)])))

(defmethod parse-list-syntax U-literal
  [[_ & syn]]
  (union (doall (map parse-syntax syn))))

(defmethod parse-list-syntax I-literal
  [[_ & syn]]
  (map->Intersection 
    {:types (doall (map parse-syntax syn))}))

(defmethod parse-list-syntax predicate-literal
  [[_ & [typ-syntax :as args]]]
  (assert (= 1 (count args)))
  (let [pred-type (parse-syntax typ-syntax)]
    (-fun [(map->arity
              {:dom [Any]
               :rng (ClassType-from Boolean)
               :pred-type pred-type
               :named-params '(a)
               :flter (map->FilterSet
                        {:then (map->TypeFilter
                                 {:var 'a
                                  :type pred-type})
                         :else (map->NotTypeFilter
                                 {:var 'a
                                  :type pred-type})})})])))

(defmethod parse-list-syntax 'quote
  [[_ & [sym :as args]]]
  (assert (= 1 (count args)))
  (assert (symbol? sym))
  (->Value sym))

(defmethod parse-list-syntax Fun-literal
  [[_ & arities]]
  (map->Fun 
    {:arities (set (doall (map parse-syntax* arities)))})) ; parse-syntax* to avoid implicit arity sugar wrapping

(defmethod parse-list-syntax :default
  [[c & inst]]
  (throw (Exception. (str "no list syntax " c))))

(extend-protocol IParseType
  IPersistentList
  (parse-syntax* [this]
    (parse-list-syntax this))

  Cons
  (parse-syntax* [this]
    (parse-list-syntax this)))

(defn- split-no-check [arity-syntax]
  (let [[dom [_ rng & opts]] (split-with #(not= '-> %) arity-syntax)]
    [dom rng (apply hash-map opts)]))

(defn- split-arity-syntax 
  "Splits arity syntax into [dom rng opts-map]"
  [arity-syntax]
  (assert (some #(= '-> %) arity-syntax) (str "Arity " arity-syntax " missing return type"))
  (split-no-check arity-syntax))

(defn- parse-filter [syn]
  (assert (vector? syn))
  (let [[nme-sym keyw type-syn] syn
        type (parse-syntax type-syn)]
    (case keyw
      :-> (map->TypeFilter
            {:var nme-sym
             :type type})
      :!-> (map->NotTypeFilter
             {:var nme-sym
              :type type}))))

(defn- parse-dom 
  "Given syntax to the left of an arrow, returns a map with keys
  :dom, :rest-type"
  [dom]
  (let [[fixed-dom [_ & rest-args]]
        (split-with #(not= '& %) dom)

        uniform-rest-syntax (when (seq rest-args)
                              (if (= '* (second rest-args))
                                (first rest-args)
                                (assert false (str "Invalid rest args syntax " dom))))

        fixed-dom-types (doall (map parse-syntax fixed-dom))
        uniform-rest-type (when rest-args
                            (parse-syntax uniform-rest-syntax))]
    {:dom fixed-dom-types 
     :rest-type uniform-rest-type}))

(extend-protocol IParseType
  IPersistentVector
  (parse-syntax* [this]
    (let [[dom rng opts-map] (split-arity-syntax this)

          {fixed-dom-types :dom
           rest-type :rest-type}
           (parse-dom dom)

          extras (into {}
                       (for [[nme syn] opts-map]
                         (cond
                           (= :filter nme) [:flter (map->FilterSet
                                                     {:then (parse-filter (:then syn))
                                                      :else (parse-filter (:else syn))})]

                           :else (throw (Exception. (str "Unsupported option " nme))))))

          rng-type (parse-syntax rng)]
      (map->arity
        (merge
          {:dom fixed-dom-types
           :rng rng-type}
          (when rest-type
            {:rest-type rest-type})))))

  nil
  (parse-syntax* [_]
    Nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unparse type syntax

(defprotocol IUnparseType
  (unparse-type* [this]))

(defmethod parse-list-syntax U-literal
  [[_ & syn]]
  (union (doall (map parse-syntax syn))))

(defn unparse-tvar-binding [tvar]
    (assert (TypeVariable? tvar))
  (if (= Any (:bnd tvar))
    (:nme tvar)
    (list (:nme tvar) '<! (unparse-type (:bnd tvar)))))

(defn unparse-type
  [type-obj]
  (if-let [scope (tvar-binding type-obj)]
    (list All-literal (doall (mapv unparse-tvar-binding scope))
          (unparse-type (remove-tvar-binding type-obj))) ;remove binding scope
    (unparse-type* type-obj)))

(def unparse unparse-type)

(defmulti unparse-filter class)

(defmethod unparse-filter FilterSet
  [{:keys [then else]}]
  {:then (unparse-filter then)
   :else (unparse-filter else)})

(defmethod unparse-filter TypeFilter
  [{:keys [var type]}]
  [var :-> (unparse-type type)])

(defmethod unparse-filter NotTypeFilter
  [{:keys [var type]}]
  [var :!-> (unparse-type type)])

(extend-protocol IUnparseType
  ClassType
  (unparse-type* [this]
    (:the-class this))

  PrimitiveClass
  (unparse-type* [this]
    (:the-class this))

  Union
  (unparse-type* [this]
    (list* U-literal (doall (map unparse-type (:types this)))))

  Intersection
  (unparse-type* [this]
    (list* I-literal (doall (map unparse-type (:types this)))))

  Fun
  (unparse-type* [this]
    (if (= 1 (count (:arities this)))
      (-> this :arities first unparse-type) ; single arity syntax
      (list* Fun-literal (doall (map unparse-type (:arities this))))))

  arity
  (unparse-type* [this]
    (let [dom (doall (map unparse-type (:dom this)))
          ;; handle named parameters
          dom (if-let [names (seq (:named-params this))]
                (doall
                  (map #(vector %1 :- %2)
                       names
                       dom))
                dom)
          rng (unparse-type (:rng this))
          flter (when-let [flter (:flter this)]
                  (unparse-filter flter))

          sig (-> (concat dom 
                          (when (:rest-type this)
                            ['& (unparse-type (:rest-type this)) '*])
                          ['-> rng]
                          (when flter
                            [:filter flter]))
                vec)]
      sig))

  Value
  (unparse-type* [{:keys [val]}]
    (if (symbol? val)
      `'~val
      val))

  QualifiedKeyword
  (unparse-type* [{:keys [kwrd]}]
    kwrd)

  ProtocolType
  (unparse-type* [{:keys [the-protocol-var]}]
    the-protocol-var)

  Seq
  (unparse-type* [this]
    (list Seqof-literal (unparse-type (:type this))))

  Vector
  (unparse-type* [this]
    (list Vectorof-literal (unparse-type (:type this))))

  ConstantVector
  (unparse-type* [this]
    (list* Vector*-literal (doall (map unparse-type (:types this)))))

  Sequential
  (unparse-type* [this]
    (list Sequentialof-literal (unparse-type (:type this))))

  ConstantSequential
  (unparse-type* [this]
    (list* Sequential*-literal (doall (map unparse-type (:types this)))))

  Map
  (unparse-type* [{[ktype vtype] :kvtype :as this}]
    (list Mapof-literal 
          [(unparse-type ktype)
           (unparse-type vtype)]))

  #_ConstantMap
  #_(unparse-type* [this]
    (list* Map*-literal (map #(vector (unparse-type (first %))
                                      (unparse-type (second %)))
                             (:kvtypes this))))

  TypeVariable
  (unparse-type* [{:keys [nme]}]
    nme)
  
  NilType
  (unparse-type* [this]
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subtyping

(declare supertype-of-all subtype-of-all supertype-of-one subtype-of-one)

(defmulti subtype?* (fn [s t]
                      [(class s) (class t)]))

;type variables

(defmethod subtype?* [TypeVariable TypeVariable]
  [{s-nme :nme} {t-nme :nme}]
  (= s-nme t-nme))

;unions

(defmethod subtype?* [Union Union]
  [{s-types :types :as s} 
   {t-types :types :as t}]
  (if (empty? t-types) ; ie. t is Nothing
    (empty? s-types)   ; s <: Nothing when s = Nothing
    (or (and (empty? t-types)
             (not (empty? s)))
        (= s t)
        (supertype-of-all t s-types))))

(defmethod subtype?* [Type Union]
  [s t]
  (subtype-of-one s (:types t)))

(defmethod subtype?* [Union Type]
  [s t]
  (supertype-of-all t (:types s)))

;singletons

(defmethod subtype?* [Value Value]
  [s t]
  (= (:val s)
     (:val t)))

(defmethod subtype?* [Value ClassType]
  [s t]
  (subtype? (ClassType-from (-> s :val class))
            t))

(defmethod subtype?* [Value PrimitiveClass]
  [s t]
  (subtype? (ClassType-from (-> s :val class))
            t))

;qualified keywords

(defmethod subtype?* [QualifiedKeyword QualifiedKeyword]
  [{s-kwrd :kwrd :as s} 
   {t-kwrd :kwrd :as t}]
  (isa? s-kwrd t-kwrd))

(defmethod subtype?* [ClassType QualifiedKeyword]
  [{s-class-sym :the-class :as s}
   {t-kwrd :kwrd :as t}]
  (let [s-class (resolve s-class-sym)]
    (isa? s-class t-kwrd)))

; keyword represents a hierarchy, Object or Keyword are not supertypes
(defmethod subtype?* [QualifiedKeyword ClassType]
  [s t]
  false)

;classes

(def ^:private extends-Seqable #{Iterable java.util.Map})

;hardcode Clojure interfaces that should "extend" Seqable
(defmethod subtype?* [ClassType ClassType]
  [{s-class-sym :the-class :as s}
   {t-class-sym :the-class :as t}]
  (let [s-class (resolve s-class-sym)
        t-class (resolve t-class-sym)]
    (or (and (identical? t-class Seqable)
             (boolean
               (some #(isa? s-class %) extends-Seqable)))
        (isa? s-class t-class))))

;protocols

(defmethod subtype?* [ProtocolType ProtocolType]
  [{s-var-sym :the-protocol-var :as s} 
   {t-var-sym :the-protocol-var :as t}]
  (let [s-var (resolve s-var-sym)
        t-var (resolve t-var-sym)]
    (isa? @s-var @t-var)))

(defmethod subtype?* [ClassType ProtocolType]
  [{s-class-sym :the-class :as s}
   {t-var-sym :the-protocol-var :as t}]
  (let [s-class (resolve s-class-sym)
        t-var (resolve t-var-sym)]
    (->> (map isa? (extenders @t-var) (repeat s-class))
      (some true?)
      boolean)))

(defmethod subtype?* [Value ProtocolType]
  [{s-val :val :as s}
   {t-var-sym :the-protocol-var :as t}]
  (let [t-var (resolve t-var-sym)]
    (satisfies? @t-var s-val)))

;nil

(def ^:private extends-nil #{ISeq Counted ILookup IObj IMeta Associative})

;hardcode Clojure interfaces that should "extend" to nil
(defmethod subtype?* [NilType ClassType]
  [s {t-class-sym :the-class :as t}]
  (let [t-class (resolve t-class-sym)]
    (boolean
      (some #(identical? t-class %) extends-nil))))

(defmethod subtype?* [NilType NilType]
  [s t]
  true)

(defmethod subtype?* [NilType ProtocolType]
  [s {t-var-sym :the-protocol-var :as t}]
  (let [t-var (resolve t-var-sym)]
    (class-satisfies-protocol? @t-var nil)))

;void primitive

(defmethod subtype?* [PrimitiveClass NilType]
  [{s-class :the-class :as s} t]
  (= 'void s-class))

(defmethod subtype?* [NilType PrimitiveClass]
  [s {t-class :the-class :as t}]
  (= 'void t-class))

;primitives

(def ^:private primitive-coersions
  {'int #{'long}})

(defmethod subtype?* [PrimitiveClass PrimitiveClass]
  [{s-class :the-class :as s}
   {t-class :the-class :as t}]
  (or (= s-class t-class)
      (let [coerce? (contains? (primitive-coersions s-class)
                               t-class)
            _ (when coerce?
                (debug "coercing primitive" s-class "->" t-class))]
        coerce?)))

(def ^:private coersions
  {'double #{Double}
   'long #{Long}
   'byte #{Byte}
   'char #{Character}
   'int #{Integer}
   'float #{Float}
   'short #{Short}
   'boolean #{Boolean}
   'void #{Void}})

(defmethod subtype?* [PrimitiveClass ClassType]
  [{s-pclass :the-class :as s} t]
  (let [possible-types (coersions s-pclass)]
    (boolean
      (some #(subtype? (ClassType-from %) t) possible-types))))

(defmethod subtype?* [ClassType PrimitiveClass]
  [{s-class-sym :the-class :as s}
   {t-pclass :the-class :as t}]
  (let [s-class (resolve s-class-sym)
        coerce? (contains? (coersions t-pclass) s-class)
        _ (when coerce?
            (debug "coercing" s-class "->" t-pclass))]
    coerce?))

;function

(defmethod subtype?* [Fun ClassType]
  [s t]
  (supertype-of-one t (map ClassType-from (-> #() class ancestors))))

(defmethod subtype?* [Fun Fun]
  [{s-arities :arities} {t-arities :arities}]
  (every? true?
          (map #(supertype-of-one % s-arities)
               t-arities)))

(defmethod subtype?* [arity arity]
  [{s-dom :dom 
    s-rng :rng
    s-rest :rest-type
    :as s}
   {t-dom :dom
    t-rng :rng
    t-rest :rest-type
    :as t}]
  (cond
    ;; simple case
    (and (not s-rest)
         (not t-rest))
    (and (subtypes? t-dom s-dom)
         (subtype? s-rng t-rng))

    (not s-rest)
    false

    (and s-rest
         (not t-rest))
    (and (subtypes?*-varargs t-dom s-dom s-rest)
         (subtype? s-rng t-rng))

    (and s-rest
         t-rest)
    (and (subtypes?*-varargs t-dom s-dom s-rest)
         (subtype? t-rest s-rest)
         (subtype? s-rng t-rng))

    :else false))

;seq

(defmethod subtype?* [Seq Seq]
  [{s-type :type :as s} 
   {t-type :type :as t}]
  (subtype? s-type t-type))

(defmethod subtype?* [Seq ProtocolType]
  [s t]
  (subtype? (ClassType-from ISeq) t))

(defmethod subtype?* [Seq ClassType]
  [s t]
  (subtype? (ClassType-from ISeq) t))

(defmethod subtype?* [Vector Seq]
  [{s-type :type :as s} 
   {t-type :type :as t}]
  (subtype? s-type t-type))

(defmethod subtype?* [Sequential Seq]
  [{s-type :type :as s} 
   {t-type :type :as t}]
  (subtype? s-type t-type))

;vectors

(defmethod subtype?* [Vector Vector]
  [{s-type :type :as s} 
   {t-type :type :as t}]
  (subtype? s-type t-type))

(defmethod subtype?* [Vector ProtocolType]
  [s t]
  (subtype? (ClassType-from IPersistentVector) t))

(defmethod subtype?* [Vector ClassType]
  [s t]
  (subtype? (ClassType-from IPersistentVector) t))

(defmethod subtype?* [ConstantVector ProtocolType]
  [s t]
  (subtype? (ClassType-from IPersistentVector) t))

(defmethod subtype?* [ConstantVector ClassType]
  [s t]
  (subtype? (ClassType-from IPersistentVector) t))

(defmethod subtype?* [ConstantVector ConstantVector]
  [{s-types :types :as s} 
   {t-types :types :as t}]
  (subtypes? s-types t-types))

(defmethod subtype?* [ConstantVector Vector]
  [{s-types :types :as s} 
   {t-type :type :as t}]
  (supertype-of-all t-type s-types))

;sequentials

(defmethod subtype?* [Sequential Sequential]
  [{s-type :type :as s}
   {t-type :type :as t}]
  (subtype? s-type t-type))

(defmethod subtype?* [ConstantSequential ProtocolType]
  [s t]
  (subtype? (ClassType-from clojure.lang.Sequential) t))

(defmethod subtype?* [Sequential ProtocolType]
  [s t]
  (subtype? (ClassType-from clojure.lang.Sequential) t))

(defmethod subtype?* [Sequential ClassType]
  [s t]
  (subtype? (ClassType-from clojure.lang.Sequential) t))

(defmethod subtype?* [ConstantVector Sequential]
  [{s-types :types :as s} 
   {t-type :type :as t}]
  (supertype-of-all t-type s-types))

(defmethod subtype?* [Vector Sequential]
  [{s-type :type :as s} 
   {t-type :type :as t}]
  (subtype? s-type t-type))

(defmethod subtype?* [ConstantVector ConstantSequential]
  [{s-types :types :as s} 
   {t-types :types :as t}]
  (subtypes? s-types t-types))

;maps

(defmethod subtype?* [Map ClassType]
  [s t]
  (subtype? (ClassType-from IPersistentMap) t))

(defmethod subtype?* [Map Map]
  [{[s-ktype s-vtype] :kvtype :as s} 
   {[t-ktype t-vtype] :kvtype :as t}]
  (subtypes? [s-ktype s-vtype]
             [t-ktype t-vtype]))

#_(defmethod subtype?* [ConstantMap ConstantMap]
  [{s-kvtypes :kvtypes :as s} 
   {t-kvtypes :kvtypes :as t}]
  (subtypes? s-kvtypes t-kvtypes))

#_(defmethod subtype?* [ConstantMap Map]
  [{s-kvtypes :kvtypes :as s} 
   {[t-ktype t-vtype] :kvtype :as t}]
  (subtypes? s-kvtypes (take (count s-kvtypes)
                             (cycle [t-ktype t-vtype]))))

;Object

(prefer-method subtype?*
  [Union Type]
  [Type ClassType])

;; everything except nil is a subtype of java.lang.Object
(defmethod subtype?* [Type ClassType]
  [s t]
  (and (isa? Object (resolve (:the-class t)))
       (not (Nil? s))))
;TODO primitives?

;default

(defmethod subtype?* [Type Type]
  [s t]
  false)

(defn subtype-of-one
  "True if s is a subtype to at least one ts"
  [s ts]
  (boolean (some #(subtype? s %) ts)))

(defn supertype-of-one
  "True if t is a supertype to at least one ss"
  [t ss]
  (boolean (some #(subtype? % t) ss)))

(defn subtype-of-all 
  "True if s is subtype of all ts"
  [s ts]
  (every? true?
          (map #(subtype? s %) ts)))

(defn supertype-of-all
  "True if t is a supertype of all ss"
  [t ss]
  (every? true?
          (map #(subtype? % t) ss)))

(defn subtypes? [ss ts]
  (and (= (count ss)
          (count ts))
       (every? true? 
               (map subtype? ss ts))))

(defn subtype? [s t]
  (subtype?* s t))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Variable Renaming

(defn- unique-variable
  ([] (-tv (gensym)))
  ([^{+T TypeVariable} t] 
   (update-in t [:nme] gensym)))

(defprotocol IVariableRename
  (-rename [this ^{+T (IPersistentMap [TypeVariable TypeVariable])} rmap] 
           "Rename all occurrences of variables in rmap in this.
           Assumes no conflicting variables (no inner scopes introduce variables
           that conflict with rmap)"))

(defn rename 
  "Rename all occurrences of variables in rmap in t"
  [^{+T Type} t 
   ^{+T (IPersistentMap [TypeVariable TypeVariable])} rmap]
  (assert (Type? t) t)
  (assert (map? rmap))
  (assert (every? TypeVariable? (concat (keys rmap) (vals rmap))))
  (let [conflicts (set/intersection (-> t tvar-binding set) (-> rmap keys set))]
    ;scope introduces conflicting variables
    (if (seq conflicts)
      (let [renames (into {}
                          (map #(vector % (unique-variable %))
                               conflicts))
            resolved-conflicts 
            (-> (-rename t renames)
              (update-tvar-binding #(replace renames %)))] ;update binding scope
        (-rename resolved-conflicts rmap))
      (-rename t rmap))))

(defn rename-all [ts vs]
  (doall (map rename ts (repeat vs))))

(extend-protocol IVariableRename
  Union
  (-rename [this rmap]
    (-> this
     (update-in [:types] #(rename-all % rmap))))

  TypeVariable
  (-rename [this rmap]
    (if-let [r (rmap this)]
      r
      this))
  
  ClassType
  (-rename [this rmap] this)

  Fun
  (-rename [this rmap]
    (-> this
      (update-in [:arities] #(set (rename-all % rmap)))))

  arity
  (-rename [this rmap]
    (-> this
      (update-in [:dom] #(rename-all % rmap))
      (update-in [:rest-type] #(when %
                                 (rename % rmap)))
      (update-in [:rng] #(rename % rmap)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Variable Elimination
;;
;; Local Type Inference, Pierce & Turner
;; Section 5.3

(defprotocol IVariableElim
  (-promote [this ^{+T (IPersistentSet TypeVariable)} vs] 
           "Promote type until type variables vs do not occur in it.
           Assumes no conflicting variables are introduced by inner scopes.")
  (-demote [this ^{+T (IPersistentSet TypeVariable)} vs] 
          "Demote type until type variables vs do not occur in it.
           Assumes no conflicting variables are introduced by inner scopes."))

(defn resolve-conflicts
  "Resolves conflicting type variables introduced by inner scopes
  by uniquely renaming them"
  [^{+T Type} t 
   ^{+T (IPersistentSet TypeVariable)} vs]
    (assert (Type? t))
    (assert (set? vs))
  (let [conflicts (set/intersection (set (tvar-binding t)) vs)]
    (if (seq conflicts)
      (rename t (into {}
                      (doall (map #(vector % (unique-variable %))
                                  conflicts))))
      t)))

(defn promote 
  "Promote type until type variables vs do not occur in it. 
  Handles conflicting inner scopes"
  [^{+T Type} t 
   ^{+T (IPersistentSet TypeVariable)} vs]
    (assert (Type? t) t)
    (assert (set? vs))
    (assert (every? TypeVariable? vs))
  (let [no-conflicts (resolve-conflicts t vs)
        frees (set (free-vars t))
        frees-in-bnds (set (mapcat #(-> % :bnd free-vars) frees))
        vs-in-bnds (set/intersection frees-in-bnds vs)]
    (if (seq vs-in-bnds)
      Any                            ; VU-Fun-2 - Give up if vs occur in bounds of variables inside t
      (-promote no-conflicts vs))))

(defn demote 
  "Demotes type until type variables vs do not occur in it. 
  Handles conflicting inner scopes"
  [^{+T Type} t 
   ^{+T (IPersistentSet TypeVariable)} vs]
    (assert (Type? t))
    (assert (set? vs))
    (assert (every? TypeVariable? vs))
  (let [no-conflicts (resolve-conflicts t vs)
        frees (set (free-vars t))
        frees-in-bnds (set (mapcat #(-> % :bnd free-vars) frees))
        vs-in-bnds (set/intersection frees-in-bnds vs)]
    (if (seq vs-in-bnds)
      Nothing                        ; VD-Fun-2 - Give up if vs occur in bounds of variables inside t
      (-demote no-conflicts vs))))

(defn- promote-all [ts vs]
  (doall (map promote ts (repeat vs))))

(defn- demote-all [ts vs]
  (doall (map demote ts (repeat vs))))

(extend-protocol IVariableElim
  Union
  (-promote [this vs]
    (union (promote-all (:types this) vs)))
  (-demote [this vs]
    (union (demote-all (:types this) vs)))

  TypeVariable
  (-promote [this vs]
    (if (vs this)
      (:bnd this)
      this))
  (-demote [this vs]
    (if (vs this)
      Nothing
      this))
  
  ClassType
  (-promote [this vs] this)
  (-demote [this vs] this)
  
  NilType
  (-promote [this vs] this)
  (-demote [this vs] this)

  Fun
  (-promote [this vs]
    (-> this
      (update-in [:arities] #(set (promote-all % vs)))))
  (-demote [this vs]
    (-> this
      (update-in [:arities] #(set (demote-all % vs)))))

  arity
  (-promote [this vs]
    (-> this
      (update-in [:dom] #(demote-all % vs))
      (update-in [:rest-type] #(when %
                                 (demote % vs)))
      (update-in [:rng] #(promote % vs))))
  (-demote [this vs]
    (-> this
      (update-in [:dom] #(promote-all % vs))
      (update-in [:rest-type] #(when %
                                 (promote % vs)))
      (update-in [:rng] #(demote % vs))))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constraint Generation
;;
;; Local Type Inference, Pierce & Turner
;; Section 5.4

(defconstrainedrecord EqConstraint [type]
  "Type R satisfies an EqConstraint [S] if (= R S)"
  {:pre [(Type? type)]})

(defconstrainedrecord SubConstraint [lower upper]
  "Type R satisfies a SubConstraint [S T] if (subtype? R S) and (subtype? R T)"
  {:pre [(Type? lower)
         (Type? upper)]})

(def constraints #{EqConstraint SubConstraint})
(def constraint? #(contains? constraints (class %)))

(defconstrainedrecord ConstraintSet [cs]
  "A map of type variables to constraints"
  {:pre [(map? cs)
         (every? TypeVariable? (keys cs))
         (every? constraint? (vals cs))]})

(defn empty-constraint-set
  "The empty xs-without-vs constraint set 
  maps each variable x in xs to the constraint [Bot, Top]"
  [xs]
  {:post [ConstraintSet?]}
    (assert (every? TypeVariable? xs))
    (assert (set? xs))
  (->ConstraintSet
    (into {}
          (map vector
               xs
               (repeat (->SubConstraint Nothing Any))))))

(defn singleton-constraint-set
  "The singleton xs-without-vs constraint set
  map variable v to constraint c, and
  maps each variable x in xs to the constraint [Bot, Top]"
  [v c xs]
  {:post [ConstraintSet?]}
    (assert (TypeVariable? v))
    (assert (constraint? c))
    (assert (set? xs))
    (assert (every? TypeVariable? xs))
    (assert (not (contains? xs v)))
  (merge (empty-constraint-set xs)
         {v c}))

(defmulti intersect-constraint
  (fn [c1 c2] [(class c1) (class c2)]))

(defmethod intersect-constraint [EqConstraint EqConstraint]
  [{l-type :type :as c1} {r-type :type :as c2}]
  (assert (= l-type r-type) "Intersect of two constraints must be both equal")
  c1)

(defmethod intersect-constraint [EqConstraint SubConstraint]
  [{l-type :type :as c1} 
   {r-lower :lower r-upper :upper :as c2}]
  (assert (and (subtype? r-lower l-type)
               (subtype? l-type r-upper)))
  c1)

(defmethod intersect-constraint [SubConstraint EqConstraint]
  [{l-lower :lower l-upper :upper :as c1}
   {r-type :type :as c2}]
  (assert (and (subtype? l-lower r-type)
               (subtype? r-type l-upper)))
  c2)

(defmethod intersect-constraint [SubConstraint SubConstraint]
  [{l-lower :lower l-upper :upper :as c1}
   {r-lower :lower r-upper :upper :as c2}]
  (->SubConstraint (union l-lower r-lower)
                   (->Intersection [l-upper r-upper])))

(defn- intersect-constraint-sets [& cs]
  (assert (every? ConstraintSet? cs))
  (apply merge-with intersect-constraint cs))

(declare constraint-gen*)

(defn constraint-gen
  "Given a set of type variables vs, a set of unknowns xs, and two
  two types s and t, calculates the minimal xs-without-vs constraint set
  C guaranteeing that (subtype? s t)"
  [vs xs s t]
    (assert (set? vs))
    (assert (set? xs))
    (assert (every? TypeVariable vs))
    (assert (every? TypeVariable xs))
    (assert (Type? s))
    (assert (Type? t))
  (let [s (resolve-conflicts s (set/union vs xs))
        t (resolve-conflicts t (set/union vs xs))]
    (constraint-gen* vs xs s t)))

(declare constraint-gen-arity)

(defn- constraint-gen*
  "Assumes types have unique variable names"
  [vs xs s t]
  {:post [ConstraintSet?]}
    (assert (set? vs))
    (assert (set? xs))
    (assert (every? TypeVariable? vs))
    (assert (every? TypeVariable? xs))
    (assert (Type? s))
    (assert (Type? t))
    ;variables xs does not occur in one type s or t
    (assert (or (empty?
                  (set/intersection 
                    (set (free-vars t))
                    xs))
                (empty?
                  (set/intersection 
                    (set (free-vars t))
                    xs))))
  (cond
    (= Any t)
    (empty-constraint-set xs)
    
    (= Nothing s)
    (empty-constraint-set xs)

    (and (contains? xs s)
         (empty?
           (set/intersection 
             (set (free-vars t))
             xs)))
    (let [r (demote t vs)
          c (->SubConstraint Nothing r)]
      (singleton-constraint-set s c (disj xs s)))

    (and (contains? xs t)
         (empty?
           (set/intersection 
             (set (free-vars s))
             xs)))
    (let [r (promote s vs)
          c (->SubConstraint r Any)]
      (singleton-constraint-set t c (disj xs t)))

    (= s t)
    (empty-constraint-set xs)

    (TypeVariable? s)
    (constraint-gen vs xs (:bnd s) t)

    (and (Fun? s)
         (Fun? t))
    (apply intersect-constraint-sets
           (for [super-arity (:arities t)]
             (constraint-gen-arity
               xs vs
               (some #(similar-arity super-arity %)
                     (:arities s))
               super-arity)))
    
    :else (throw (Exception. "Cannot generate constraint"))))

(defn- align-doms [a-sub a-sup]
  (cond
    (and (:rest-type a-sub)
         (:rest-type a-sup))
    (cond
      (= (count (:dom a-sub))
         (count (:dom a-sup)))
      [(concat (:dom a-sub) [(:rest-type a-sub)])
       (concat (:dom a-sup) [(:rest-type a-sup)])]

      (< (count (:dom a-sub))
         (count (:dom a-sup)))
      (let [l (concat (:dom a-sub) [(:rest-type a-sub)])]
        [l 
         (take (count l)
               (concat (:dom a-sup) (repeat (:rest-type a-sup))))])

      :else
      (let [r (concat (:dom a-sup) [(:rest-type a-sup)])]
        [(take (count r)
               (concat (:dom a-sub) [(:rest-type a-sub)]))
         r]))

    (:rest-type a-sub)
    (let [r (:dom a-sup)]
      [(take r 
             (concat (:dom a-sub) (repeat (:rest-type a-sub))))
       r])

    (:rest-type a-sup)
    (let [l (:dom a-sub)]
      [l
       (take (count l)
             (concat (:dom a-sup) (repeat (:rest-type a-sup))))])

    :else
    [(:dom a-sub)
     (:dom a-sup)]))

(defn constraint-gen-arity 
  [vs xs a-sub a-sup]
  {:post [ConstraintSet?]}
    (assert (arity? a-sub))
    (assert (arity? a-sup))
  (let [[sub-dom sup-dom]
        (align-doms a-sub a-sup)
        
        dom-constraint-sets (doall (map constraint-gen sup-dom sub-dom))
        rng-constraint-set (constraint-gen (:rng a-sub) (:rng a-sup))]
    (apply intersect-constraint-sets rng-constraint-set dom-constraint-sets)))

(declare match-constraint-arity)

(defn match-constraint
  "Returns a vector [u c]. u is equal to whichever of s or t is concrete, and
  c is a constraint set whose solutions make s and t identical"
  [vs xs s t]
  {:post [(vector? %)
          (Type? (first %))
          (ConstraintSet? (second %))]}
    (assert (set? vs))
    (assert (set? xs))
    (assert (every? TypeVariable? vs))
    (assert (every? TypeVariable? xs))
    (assert (Type? s))
    (assert (Type? t))
    ;variables xs does not occur in one type s or t
    (assert (or (empty?
                  (set/intersection 
                    (set (free-vars t))
                    xs))
                (empty?
                  (set/intersection 
                    (set (free-vars t))
                    xs))))
  (cond
    (and (= Any s)
         (= Any t))
    [Any (empty-constraint-set xs)]

    (and (= Nothing s)
         (= Nothing t))
    [Nothing (empty-constraint-set xs)]

    (and (contains? xs s)
         (empty?
           (set/intersection
             (set (free-vars t))
             (set/union vs xs))))
    [t (singleton-constraint-set s (->EqConstraint t) (disj xs s))]

    (and (contains? xs s)
         (empty?
           (set/intersection
             (set (free-vars s))
             (set/union vs xs))))
    [s (singleton-constraint-set t (->EqConstraint s) (disj xs t))]

    (and (= s t)
         (not (contains? xs s)))
    [s (empty-constraint-set xs)]

    (and (Fun? s)
         (Fun? t))
    (apply intersect-constraint-sets
           (for [super-arity (:arities t)]
             (match-constraint-arity
               xs vs
               (some #(similar-arity super-arity %)
                     (:arities s))
               super-arity)))))

(defn match-constraint-arity
  [vs xs a-sub a-sup]
    (assert (arity? a-sub))
    (assert (arity? a-sup))
  (let [[sub-dom sup-dom]
        (align-doms a-sub a-sup)
        
        dom-constraint-sets (doall (map match-constraint sup-dom sub-dom))
        rng-constraint-set (match-constraint (:rng a-sub) (:rng a-sup))]
    (apply intersect-constraint-sets rng-constraint-set dom-constraint-sets)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Calculating Variance

(def covariant ::covariant)
(def contravariant ::contravariant)
(def invariant ::invariant)

(def variances #{covariant contravariant invariant})

(def any-variance ::any-variance)

(doseq [v variances]
  (derive v any-variance))

(def ^:dynamic *current-variance*)
(set-validator! #'*current-variance* variances)

(defmulti update-variance vector)

(defmethod update-variance [nil any-variance] [_ v] v)
(defmethod update-variance [any-variance nil] [v _] v)

(defmethod update-variance [covariant covariant] [& v] covariant)
(defmethod update-variance [contravariant contravariant] [& v] contravariant)

(defmethod update-variance [contravariant covariant] [& v] invariant)
(defmethod update-variance [covariant contravariant] [& v] invariant)

(defmethod update-variance [any-variance invariant] [& v] invariant)
(defmethod update-variance [invariant any-variance] [& v] invariant)

(prefer-method update-variance 
               [invariant any-variance]
               [any-variance invariant])

(defmacro with-flipped-variance [& body]
  `(binding [*current-variance* (if (= covariant *current-variance*)
                                  contravariant
                                  covariant)]
     ~@body))

(defmacro with-contravariance [& body]
  `(binding [*current-variance* contravariant]
     ~@body))

(defmacro with-covariance [& body]
  `(binding [*current-variance* covariant]
     ~@body))

(defprotocol IVariance
  (collect-variances [this xs m]))

(defn calculate-variances [t xs]
    (assert (Type? t))
    (assert (set? xs))
    (assert (every? TypeVariable? xs))
    (assert (bound? #'*current-variance*))
  (let [t (resolve-conflicts t xs)]
    (collect-variances t xs {})))

(extend-protocol IVariance
  NilType
  (collect-variances [this xs m] m)

  ClassType
  (collect-variances [this xs m] m)

  Vector
  (collect-variances [this xs m] 
    (collect-variances (:type this) xs m))

  TypeVariable
  (collect-variances [this xs m]
    (let [bnd-variances (collect-variances (:bnd this) xs m)]
      (merge-with 
        update-variance
        bnd-variances
        (when (xs this)
          (update-in m [this] #(update-variance % *current-variance*))))))

  Union 
  (collect-variances [this xs m]
    (apply merge-with update-variance
           (doall (map #(collect-variances % xs m) (:types this)))))
  
  Fun
  (collect-variances [this xs m]
    (apply
      merge-with update-variance
      (doall (map #(collect-variances % xs m) (:arities this)))))

  arity
  (collect-variances [this xs m]
    (let [dom-variances (with-flipped-variance
                          (doall 
                            (map #(collect-variances % xs m)
                               (concat (:dom this)
                                       (when (:rest-type this)
                                         [(:rest-type this)])))))
          rng-variances (collect-variances (:rng this) xs m)]
      (apply merge-with update-variance
             (concat dom-variances [rng-variances])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Calculating Type Arguments
;;
;; Local Type Inference, Pierce & Turner
;; Section 5.7

(defprotocol IMaxMinType
  (max-type [this])
  (min-type [this]))

(extend-protocol IMaxMinType
  EqConstraint
  (max-type [this] (:type this))
  (min-type [this] (:type this))

  SubConstraint
  (max-type [this] (:upper this))
  (min-type [this] (:lower this)))

(defn gen-substitution 
  "Given a type and a satisfiable constraint set, return the substitution map"
  [t xs cs]
  {:post [(map? %)
          (every? TypeVariable? (keys %))
          (every? Type? (vals %))]}
    (assert (Type? t))
    (assert (ConstraintSet? cs))
    (assert (set? xs))
    (assert (every? TypeVariable? xs))
  (let [cs (:cs cs)
        variance (with-covariance
                   (calculate-variances t xs))]
    (into {}
          (for [x xs]
            [x
             (cond
               (= covariant (variance x))
               (min-type (cs x))

               (= contravariant (variance x))
               (max-type (cs x))

               (= invariant (variance x))
               (min-type (cs x))

               ;;TODO rigid?

               :else
               (throw (Exception. "No variance")))]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Inference

;; Bidirectional checking (Local Type Inference (2000) Pierce & Turner, Section 4)

(declare tc-expr)

(defn tc-expr-check [expr expected-type]
  (let [expr (tc-expr expr :expected-type expected-type)]
    (assert-subtype (type-key expr) expected-type)
    expr))

(defmulti tc-expr 
  (fn [expr & opts] (:op expr)))

(defn tc-exprs [exprs]
  (doall (map tc-expr exprs)))

;number

(defmethod tc-expr :number
  [{:keys [val] :as expr} & opts]
  (assoc expr
         type-key (->Value val)))

;constant

(defmulti constant-type class)

(defmethod constant-type nil
  [_]
  Nil)

(defmethod constant-type IPersistentMap
  [m]
  (->Map [(union (doall (map constant-type (keys m))))
          (union (doall (map constant-type (vals m))))]))

(defmethod constant-type IPersistentList
  [l]
  (->ConstantSequential (doall (map constant-type l))))

(defmethod constant-type IPersistentVector
  [v]
  (->ConstantVector (doall (map constant-type v))))

#_(defmethod constant-type IPersistentMap
  [r]
  (->ConstantMap (doall (map constant-type (apply concat r)))))

(defmethod constant-type Ratio
  [r]
  (->Value r))

(defmethod constant-type Keyword
  [kw]
  (if (namespace kw)
    (->QualifiedKeyword kw)
    (->Value kw)))

(defmethod constant-type Long
  [s]
  (->Value s))

(defmethod constant-type Symbol
  [s]
  (->Value s))

(defmethod constant-type Character
  [c]
  (->Value c))

(defmethod tc-expr :constant
  [{:keys [val] :as expr} & opts]
  (assoc expr
         type-key (constant-type val)))

;keyword

(defmethod tc-expr :keyword
  [{:keys [val] :as expr} & opts]
  (assoc expr
         type-key (->Value val)))

;string

(defmethod tc-expr :string
  [{:keys [val] :as expr} & opts]
  (assoc expr
         type-key (->Value val)))

;if

(defmethod tc-expr :if
  [expr & opts]
  (let [{:keys [test then else] :as expr}
        (-> expr
          (update-in [:test] tc-expr)
          (update-in [:then] tc-expr)
          (update-in [:else] tc-expr))]
    (assoc expr
           type-key (union [(type-key then)
                            (type-key else)]))))

;do

(defmethod tc-expr :do
  [expr & opts]
  (let [{cexprs :exprs
         :as expr}
        (-> expr
          (update-in [:exprs] #(map tc-expr %)))]
    (assoc expr
           type-key (-> cexprs last type-key))))

;nil

(defmethod tc-expr :nil
  [expr & opts]
  (assoc expr
         type-key Nil))

;def

(defmethod tc-expr :def
  [{:keys [init-provided var] :as expr} & opts]
  (debug "def:" var)
  (cond 
    (.isMacro var) expr
    :else
    (let [expr (-> expr
                 (update-in [:init] #(if init-provided
                                       (tc-expr-check % (type-of var))
                                       %)))]
      (assoc expr
             type-key (ClassType-from Var)))))

;fn

(defmethod tc-expr :fn-expr
  [{:keys [methods] :as expr} & {:keys [expected-type]}]
  (let [{cmethods :methods
         :as expr}
        (-> expr
          (update-in [:methods] (if expected-type
                                  (fn [m]
                                    (doall (map #(tc-expr % :expected-type expected-type) m)))
                                  tc-exprs)))]
    (assoc expr
           type-key (-fun (map type-key cmethods)))))

(defn check-fn-method 
  [{:keys [required-params rest-param] :as expr} expected-fun-type]
  (let [[mtched-arity :as mtched-arities]
        (filter #(or (and (not rest-param)
                          (= (count required-params)
                             (count (:dom %))))
                     (and rest-param
                          (<= (count required-params)
                              (count (:dom %)))))
                (:arities expected-fun-type))

        _ (assert (= 1 (count mtched-arities)))

        dom-syms (map :sym required-params)
        rest-sym (:sym rest-param)

        dom-types (:dom mtched-arity)
        rng-type (:rng mtched-arity)
        rest-type (:rest-type mtched-arity)

        actual-rest-arg-type (when rest-param
                               (->Sequential rest-type))

        expr
        (-> expr
          (update-in [:body] #(with-local-types
                                (into {}
                                      (map vector 
                                           (concat dom-syms (when rest-param
                                                              [rest-sym]))
                                           (concat dom-types (when rest-param
                                                               [actual-rest-arg-type]))))
                                (tc-expr %))))]
    (assoc expr
           type-key mtched-arity)))

(defmacro parse-params [dom-syn]
  `(parse-dom '~dom-syn))

(def annotate-param-syntax :-)
(def annotate-dom-syntax :-params)

(defn synthesize-fn-method
  [{:keys [required-params rest-param] :as expr}]
  (letfn [(meta-type-annot [expr]
            (assert (-> expr :sym meta (find annotate-param-syntax))
                    (str "No type for parameter " (-> expr :sym)))
            (-> expr :sym meta (get annotate-param-syntax) parse))]
    (let [dom-syms (map :sym required-params)
          rest-sym (:sym rest-param)

          meta-annots-reqd (doall (map meta-type-annot required-params))
          meta-annot-rst (when rest-param
                           (meta-type-annot rest-param))

          _ (assert (every? Type? (concat meta-annots-reqd (when rest-param
                                                             [meta-annot-rst])))
                    "All function parameters must be annotated in synthesis mode")

          dom-types meta-annots-reqd
          rest-type meta-annot-rst
          actual-rest-arg-type (when rest-param
                                 (->Sequential rest-type))

          {cbody :body
           :as expr} 
          (-> expr
            (update-in [:body] #(with-local-types
                                  (into {}
                                        (map vector 
                                             (concat dom-syms (when rest-param
                                                                [rest-sym]))
                                             (concat dom-types (when rest-param
                                                                 [actual-rest-arg-type]))))
                                  (tc-expr %))))

          rng-type (type-key cbody)]
      (assoc expr
             type-key (map->arity
                        {:dom dom-types
                         :rest-type rest-type
                         :rng rng-type})))))

(defmethod tc-expr :fn-method
  [{:keys [required-params rest-param] :as expr} & {expected-fun-type :expected-type}]
  (cond
    expected-fun-type (check-fn-method expr expected-fun-type)
    :else (synthesize-fn-method expr)))

;local binding expr

(defmethod tc-expr :local-binding-expr
  [{:keys [local-binding] :as expr} & opts]
  (debug "local-binding-expr:" (:sym local-binding) ":-" (type-of (:sym local-binding)))
  (assoc expr
         type-key (type-of (:sym local-binding))))

;var

(defmethod tc-expr :var
  [{:keys [var] :as expr} & opts]
  (assoc expr
         type-key (type-of var)))

;invoke

(defn- invoke-type [arg-types {:keys [arities] :as fun-type}]
  (let [[mtched-arity :as mtched-arities]
        (filter #(or (and (not (:rest-type %))
                          (= (count (:dom %))
                             (count arg-types)))
                     (and (:rest-type %)
                          (<= (count (:dom %))
                              (count arg-types))))
                arities)

        _ (assert (= 1 (count mtched-arities))
                  (str "Invoke args " (with-out-str (pr (map unparse arg-types)))
                       " do not match any arity in "
                       (unp fun-type)))

        xs (-> fun-type tvar-binding set) 
        _ (assert (empty?
                    (set/intersection 
                      (set (mapcat #(free-vars (:bnd %)) xs))
                      xs))
                  "xs cannot occur in bounds")

        bnd-cons (map #(constraint-gen #{} xs % (:bnd %)) xs)
        dom-cons (map #(constraint-gen #{} xs %1 %2)
                      arg-types
                      (concat (:dom mtched-arities)
                              (repeat (:rest-type mtched-arities))))

        subst (gen-substitution mtched-arity xs (apply intersect-constraint-sets
                                                  (concat bnd-cons dom-cons)))

        _ (prn subst)

        _ (debug "invoke type" (unp mtched-arity))
        
        _ (doall (map assert-subtype arg-types (concat (:dom mtched-arity)
                                                       (when (:rest-type mtched-arity)
                                                         (repeat (:rest-type mtched-arity))))))]
    (:rng mtched-arity)))

(defmethod tc-expr :invoke
  [expr & opts]
  (debug "invoke:" (or (-> expr :fexpr :var)
                         "??"))
  (let [{cfexpr :fexpr
         cargs :args
         :as expr}
        (-> expr
          (update-in [:fexpr] tc-expr)
          (update-in [:args] tc-exprs))]
  (assoc expr
         type-key (invoke-type (map type-key cargs)
                               (type-key cfexpr)))))

;let

(defn- binding-init-map 
  "Returns a map with a single entry, associating
  the name of the binding with its type"
  [{:keys [local-binding init] :as binding-init}]
  (let [init (tc-expr init)
        {:keys [sym]} local-binding]
    {sym (type-key init)}))

(defmethod tc-expr :let
  [{:keys [binding-inits] :as expr} & opts]
  (let [[binding-inits local-env]
        (loop [local-env {}  ;accumulate local environment
               binding-inits binding-inits
               typed-binits []]
          (if (empty? binding-inits)
            [typed-binits local-env]
            (let [tbinit (-> (first binding-inits)
                           (update-in [:init]
                                      #(with-local-types local-env ;with local environment so far
                                                         (tc-expr %))))
                  env-entry {(-> tbinit :local-binding :sym) 
                             (-> tbinit :init type-key)}
                  local-env (merge local-env env-entry)]
              (recur local-env
                     (next binding-inits)
                     (concat typed-binits [tbinit])))))

        expr
        (-> expr
          (update-in [:binding-inits] (constantly binding-inits))
          (update-in [:body] #(with-local-types local-env
                                (tc-expr %))))]
    (assoc expr
           type-key (type-key (:body expr)))))

;static-method

(defn tc-method [{:keys [method] :as expr}]
  (let [method-type (method->Fun method)
        {cargs :args
         :as expr} 
        (-> expr
          (update-in [:args] tc-exprs))]
    (assoc expr
           type-key (invoke-type (map type-key cargs)
                                 method-type))))

(defmethod tc-expr :static-method
  [{:keys [method method-name class] :as expr} & opts]
  (assert method (str "Unresolvable static method " method-name))
  (debug "static-method:" class method-name)
  (tc-method expr))

;instance-method

(defmethod tc-expr :instance-method
  [{:keys [method method-name] :as expr} & opts]
  (assert method (str "Unresolvable instance method " method-name))
  (tc-method expr))

;static-field

(+T field->Type [java.lang.reflect.Field -> Type])
(defn field->Type [field]
  (resolve-symbol (:type field)))

(defmethod tc-expr :static-field
  [{:keys [field field-name] :as expr} & opts]
  (assert field (str "Unresolvable static field " field-name))
  (assoc expr
         type-key (field->Type field)))

;instance-field

(defmethod tc-expr :instance-field
  [{:keys [field field-name] :as expr} & opts]
  (assert field (str "Unresolvable instance field " field-name))
  (assoc expr
         type-key (field->Type field)))
;map

(defmethod tc-expr :map
  [expr & opts]
  (let [{ckeyvals :keyvals
         :as expr}
        (-> expr
          (update-in [:keyvals] tc-exprs))
        
        keytype (union (map type-key (-> (apply hash-map ckeyvals) keys)))
        valtype (union (map type-key (-> (apply hash-map ckeyvals) vals)))]
    (assoc expr
           type-key (->Map [keytype valtype]))))

;vector

(defmethod tc-expr :vector
  [expr & opts]
  (let [{cargs :args
         :as expr}
        (-> expr
          (update-in [:args] tc-exprs))]
    (assoc expr
           type-key (->ConstantVector (map type-key cargs)))))

;emptyexpr

(defmulti empty-types class)

(defmethod empty-types IPersistentMap [m] (->Map [Nothing Nothing]))
(defmethod empty-types IPersistentVector [m] (->Vector Nothing))

(defmethod tc-expr :empty-expr
  [{:keys [coll] :as expr} & opts]
  (assoc expr
         type-key (empty-types coll)))

;case

(defmethod tc-expr :case*
  [expr & opts]
  (let [{cthens :thens
         cdefault :default
         :as expr}
        (-> expr
          (update-in [:tests] tc-exprs)
          (update-in [:thens] tc-exprs)
          (update-in [:default] tc-expr))]
  (assoc expr
         type-key (union (map type-key (concat (when (not= Nothing (type-key cdefault)) ;; hmm should probably filter 
                                                                                        ;; out Nothings from unions
                                                [cdefault])
                                               cthens))))))

;throw

(defmethod tc-expr :throw
  [expr & opts]
  (let [{cexception :exception
         :as expr}
        (-> expr
          (update-in [:exception] tc-expr))

        _ (assert-subtype (type-key cexception) (ClassType-from Throwable))]
    (assoc expr
           type-key Nothing)))

;try

(defmethod tc-expr :try
  [expr & opts]
  (let [{ctry-expr :try-expr
         ccatch-exprs :catch-exprs
         :as expr}
        (-> expr
          (update-in [:try-expr] tc-expr)
          (update-in [:finally-expr] #(if %
                                        (tc-expr %)
                                        %))
          (update-in [:catch-exprs] tc-exprs))]
    (assoc expr
           type-key (union (map type-key (cons ctry-expr ccatch-exprs))))))

(defmethod tc-expr :catch
  [{:keys [local-binding class] :as expr} & opts]
  (let [lenv {(:sym local-binding) (ClassType-from class)}

        {chandler :handler
         :as expr}
        (-> expr
          (update-in [:handler] #(with-local-types lenv
                                   (tc-expr %))))]
    (assoc expr
           type-key (type-key chandler))))


(+T constructor->Fun [clojure.reflect.Constructor -> Fun])
(defn constructor->Fun [{:keys [parameter-types declaring-class] :as ctor}]
  (assert ctor "Unresolved constructor")
  (map->Fun
    {:arities #{(map->arity 
                  {:dom (doall (map parse parameter-types))
                   :rng (parse declaring-class)})}}))

(defmethod tc-expr :new
  [{:keys [ctor] :as expr} & opts]
  (let [ctor-fun (constructor->Fun ctor)

        {cargs :args
         :as expr}
        (-> expr
          (update-in [:args] tc-exprs))]
    (assoc expr
           type-key (invoke-type (map type-key cargs) ctor-fun))))

;import

(defmethod tc-expr :import*
  [expr & opts]
  (assoc expr
         type-key (ClassType-from Class)))

;var

(defmethod tc-expr :the-var
  [expr & opts]
  (assoc expr
         type-key (ClassType-from Var)))

(comment

  (with-type-anns
    {float? (predicate Float)
     integer? (predicate Integer)
     takes-float [Float -> Boolean]
     takes-integer [Integer -> Boolean]
     occur [(U Float Integer) -> Boolean]}
    (synthesize-form 
      (do
        (declare takes-integer takes-float)
        (defn occur [a]
          (cond
            (float? a) (takes-float a)
            (integer? a) (takes-integer a)
            :else false)))))

  (with-type-anns
    {identity (All [a]
                   [a -> a])}
    (synthesize-form
      (do
        (defn identity [b]
          b)
        (identity 1))))

  (with-type-anns
    {both-same (All [a]
                    [a a -> a])}
    (synthesize-form
      (do
        (declare both-same)
        (both-same 1 "a"))))

  (tc-expr (+ 1 1))

  (check-namespace 'typed.example.typed)
  (check-namespace 'typed.core)

)