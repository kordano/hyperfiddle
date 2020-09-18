(ns hyperfiddle.context
  (:require
   [contrib.data :refer [empty->nil]]
   [contrib.polymorphism :as p]
   [datascript.parser :as datascript]
   [hyperfiddle.spec :as s]))

; #::{:level #:find{:rel :rel-entry :coll :tuple :scalar}
;     :value "the value"}

(declare focus, focus*, focus-in)

(defn eldest
  ([ctx]
   (when ctx
     (::parent ctx ctx)))
  ([ctx key]
   (when ctx
     (or (eldest (::parent ctx) key)
         (get ctx key)))))

(defn youngest
  [ctx key]
  (when ctx
    (if (contains? ctx key)
      (get ctx key)
      (recur (::parent ctx) key))))

(defn ancestory
  [ctx key]
  (when ctx
    (lazy-seq
     (cons (get ctx key) (ancestory (::parent ctx) key)))))

(defn identifier?
  [{:keys [::spec ::attribute]}]
  (or
   (= :db/id attribute)
   (= :db/ident attribute)
   (-> spec :type (= ::s/identifier))))

(defmulti on-focus
  (fn [ctx]
    (:type (::spec ctx))))

(defmethod on-focus :default
  [ctx]
  ctx)

(def focus (p/XMultiFn. 'focus
                        (fn [ctx props]
                          (:type (::spec ctx)))
                        nil
                        nil
                        (fn [f & args] (on-focus (apply f args)))
                        :default
                        (get-global-hierarchy)
                        (atom {})
                        (atom {})
                        (atom {})
                        (atom {})))

; (defmulti focus
;   (fn [ctx]
;     (:type (::spec ctx))))

(defmethod focus ::s/fn
  [ctx element]
  {::value (-> ctx ::value)
   ::spec (-> ctx ::spec (get element))
   ::focus element
   ::parent ctx})

(defmethod focus ::s/coll
  [ctx index]
  (merge
   {::spec (-> ctx ::spec :children first)
    ::parent ctx}
   (when index
     {::value (-> ctx ::value (nth index))
      ::focus index})))

(defmethod focus ::s/keys
  [ctx attribute]
  ; (println 'focus 'keys!)
  (let [spec (->> ctx ::spec :children (filter (fn [spec] (= attribute (:name spec)))) first)
        value (-> ctx ::value (get attribute))]
    {::value value
     ::attribute attribute
     ::spec spec
     ::focus attribute
     ::parent ctx}))

(defmethod on-focus ::s/keys
  [ctx]
  (let [ctx (update ctx ::value (fn [v] (if (vector? v) (first v) v))) ; hack for a hack
        id-name (->> ctx ::spec :children (filter #(= ::s/identifier (:type %))) first :name)]
    (merge
     ctx
     {::entity (-> ctx ::value (get id-name))})))

(defmethod focus ::s/identifier
  [ctx]
  {::value (-> ctx ::value)
   ::attribute (-> ctx ::attribute)
   ::spec (-> ctx ::spec :children first)
   ::entity (-> ctx ::value)
   ::parent ctx})

(defmethod focus :default
  [ctx element]
  (throw
    (ex-info "Invalid focus type / element combination"
             {::type (:type (::spec ctx))})))

(defn focusable?
  [ctx]
  (not= (get-method focus (:type (::spec ctx))) (get-method focus :default)))

(defn focus-in
  [ctx path]
  (loop [ctx ctx
         path path]
    (if (empty? path)
      ctx
      (recur (focus ctx (first path)) (rest path)))))

(defmulti focus*
  (fn [ctx]
    (:type (::spec ctx))))

(defmethod focus* ::s/coll
  [ctx]
  (map (fn [i] (focus ctx i)) (-> ctx ::value count range)))

(defmethod focus* ::s/keys
  [ctx]
  (into {} (map (fn [a] [a (focus ctx a)]) (->> ctx ::spec :children (map :name)))))

(defmethod focus* ::s/predicate
  [ctx]
  (throw (ex-info "Can't focus* a scalar" {::value (::value ctx)})))

(defmethod focus* :default
  [ctx]
  (throw (ex-info "Unexpected Type" {::context ctx})))

(defn safe-deref
  [any]
  (when any @any))

(defn ctx0-adapter
  [{rt :runtime pid :partition-id :as ctx0}]
  {::runtime rt
   ::partition-id pid
   ::fiddle (safe-deref (:hypercrud.browser/fiddle ctx0))
   ::spec (s/ctx->spec ctx0)
   ::value (safe-deref (:hypercrud.browser/result ctx0))})
