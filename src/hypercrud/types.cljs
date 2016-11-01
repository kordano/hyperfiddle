(ns hypercrud.types
  (:require [cljs.reader :as reader]
            [hypercrud.util :as util]))


(deftype DbId [id conn-id]
  ;Object (toString [this] (str "#DbId" (pr-str [id conn-id])))
  IComparable (-compare [x y] (compare (.-id x) (.-id y)))
  IPrintWithWriter (-pr-writer [_ writer _]
                     (-write writer (str "#DbId" (pr-str [id conn-id]))))
  IHash (-hash [this] (hash [id conn-id]))
  IEquiv (-equiv [this other] (= (hash this) (hash other))))

(def read-DbId #(apply ->DbId %))


(deftype Entity [dbgraph dbid ^:mutable data]
  ILookup
  (-lookup [_ k] (get data k))
  (-lookup [_ k not-found] (get data k not-found))

  IHash (-hash [this] (hash [dbid dbgraph]))
  IEquiv (-equiv [this other] (= (hash this) (hash other)))

  IPrintWithWriter (-pr-writer [_ writer _]
                     (let [data (util/map-values (fn [v]
                                                   (if (instance? Entity v)
                                                     (.-dbid v)
                                                     v))
                                                 data)]
                       (-write writer (str "#Entity" (pr-str [dbid data]))))))


(comment
  (-> (DbId. 123 :tinder) (Entity. facebook-db) :propfile/name :asdf :asdf))


(deftype DbVal [conn-id t]
  ;Object (toString [this] (str "#DbVal" (pr-str [conn-id t])))
  IPrintWithWriter (-pr-writer [_ writer _]
                     (-write writer (str "#DbVal" (pr-str [conn-id t]))))
  IHash (-hash [this] (hash [conn-id t]))
  IEquiv (-equiv [this other] (= (hash this) (hash other))))

(def read-DbVal #(apply ->DbVal %))


(reader/register-tag-parser! 'DbId read-DbId)
(reader/register-tag-parser! 'DbVal read-DbVal)


(deftype DbIdTransitHandler []
  Object
  (tag [_ v] "DbId")
  (rep [_ v] [(.-id v) (.-conn-id v)])
  (stringRep [_ v] nil)
  (getVerboseHandler [_] nil))


(defn DbIdTransitReader [v] (apply ->DbId v))


(deftype DbValTransitHandler []
  Object
  (tag [_ v] "DbVal")
  (rep [_ v] [(.-conn-id v) (.-t v)])
  (stringRep [_ v] nil)
  (getVerboseHandler [_] nil))


(defn DbValTransitReader [v] (apply ->DbVal v))
