(ns hyperfiddle.io.datomic.hydrate-requests
  (:require
   [cats.core :as cats :refer [mlet]]
   [cats.monad.either :as either]
   [cats.monad.exception :as exception :refer [try-on]]
   [contrib.do :refer [from-result do-result]]
   [contrib.ct :refer [silence!]]
   [clojure.set :as set]
   [clojure.string :as string]
   [contrib.data :as data :refer [cond-let map-values parse-query-element]]
   [contrib.datomic]
   [contrib.do :as do]
   [contrib.pprint :refer [pprint-str]]
   [contrib.try$ :refer [try-either]]
   [hyperfiddle.api :as hf]
   [hyperfiddle.security]
   [hyperfiddle.transaction :refer [expand-hf-tx]]
   [taoensso.timbre :as timbre]))

(defrecord SecureDbWith [db id->tempid])

; todo i18n
(def ERROR-BRANCH-PAST ":hyperfiddle.error/basis-stale Branching the past is currently unsupported, please refresh your basis by refreshing the page")

(defn build-get-secure-db-with+ [domain partitions-f db-with-lookup local-basis ?subject]
  {:pre [(map? local-basis)
         (not-any? nil? (vals local-basis))]}
  (letfn [(get-secure-db-from-branch+ [partitions pid dbname]
            {:pre [pid]}
            (if-not (get-in partitions [pid :is-branched])
              (get-secure-db-from-branch+ partitions (get-in partitions [pid :parent-pid]) dbname)
              (or (get-in @db-with-lookup [pid dbname])
                  (let [tx (get-in partitions [pid :stage dbname])
                        ;_ (timbre/debug "tx:" #_domain dbname (pr-str tx))
                        db-with+ (mlet [t (or (some-> (get local-basis dbname) exception/success)
                                              (exception/failure (ex-info (str "Basis not found") {:dbname dbname :local-basis local-basis})))
                                        init-db-with (if-let [parent-pid (get-in partitions [pid :parent-pid])]
                                                       (get-secure-db-from-branch+ partitions parent-pid dbname)
                                                       (exception/try-on
                                                         (let [$ (->> (hf/connect domain dbname)
                                                                      (hf/with-db))]
                                                           {:db-with $
                                                            :secure-db (hf/as-of $ t)})))]
                                   ; is it a history query? (let [db (if (:history? dbval) (d/history db) db)])
                                   (if (empty? tx)
                                     (cats/return init-db-with)
                                     (mlet [:let [{:keys [db-with id->tempid with?]} init-db-with]
                                            _ (if (and (not with?) (not= t (hf/basis-t db-with)))
                                                ; can only run this assert once, on the first time a user d/with's
                                                ; every subsequent d/with, the new db's basis will never again match the user submitted basis
                                                ; however this is fine, since the original t is already known good
                                                (exception/failure (RuntimeException. ERROR-BRANCH-PAST))
                                                (exception/success nil))

                                            ; Must Hydrate schemas to validate the stage
                                            ; Validator needs:
                                            ; - schema, in non-local datomic configurations we should reuse the schema we have
                                            ; - the domain's relevant hf-db with security metadata e.g. database owners
                                            ; - maybe: the dbval with basis right before this tx, to allow for additional queries (slow in non-local datomic config)
                                            tx (binding [hf/*subject* ?subject
                                                         hf/*$* db-with
                                                         hf/*domain* domain]
                                                 (with-bindings (hf/bindings domain)
                                                   (try-on (expand-hf-tx (hf/process-tx db-with domain dbname ?subject tx)))))
                                            ;_ (assert schema "needed for d/with") ; not available for hydrate-schemas in request bootstrapping
                                            {:keys [db-after tempids]} (exception/try-on (hf/with db-with {:tx-data tx
                                                                                                          #_(if-not schema
                                                                                                            tx ; bootstrapping
                                                                                                            (coerce-tx schema tx))}))]
                                       ; as-of/basis-t gymnastics:
                                       ; https://gist.github.com/dustingetz/39f28f148942728c13edef1c7d8baebf/ee35a6af327feba443339176d371d9c7eaff4e51#file-datomic-d-with-interactions-with-d-as-of-clj-L35
                                       ; https://forum.datomic.com/t/interactions-of-d-basis-t-d-as-of-d-with/219
                                       (exception/try-on
                                         {:db-with db-after
                                          :secure-db (hf/as-of db-after (hf/basis-t db-after))
                                          :with? true
                                          ; todo this merge is excessively duplicating data to send to the client
                                          :id->tempid (merge id->tempid (set/map-invert tempids))}))))]
                    (swap! db-with-lookup assoc-in [pid dbname] db-with+)
                    db-with+))))]
    (fn [dbname pid]
      {:pre [dbname pid]}
      (->> (get-secure-db-from-branch+ (partitions-f) pid dbname)
           (cats/fmap (fn [internal-secure-db]
                        (->SecureDbWith (:secure-db internal-secure-db) (:id->tempid internal-secure-db))))))))

; this is legacy garbage
; todo just catch and generate exceptions inside get-secure-db-with and hydrate-request*
(defn- error-cleaner [e req]
  (timbre/error e)                                          ; log it before this "cleaner" destroys all useful information, shouldn't be necessary
  (let [error-str (str e)
        parsed-soup (cond-let
                      [msg #_(re-find #"(?s)^.+ :db.error/datoms-conflict (.+)$" error-str)
                       (some-> (string/split error-str #"^.+ :db\.error/datoms-conflict ") second)]
                      [:db.error/datoms-conflict msg
                       "Hint: Hyperfiddle has generated an invalid Datomic transaction. If you are using the staging area, this is
                       probably a 'merge conflict' which must be reconciled by editing the staging area by hand. If it is a buggy
                       widget, please file an issue."]
                      [[match msg] (re-find #"^.+ :db.error/invalid-entity-id (.+)$" error-str)] [:db.error/invalid-entity-id msg]
                      [[match msg] (re-find #"^.+ :db.error/insufficient-binding (.+)$" error-str)]
                      [:db.error/insufficient-binding msg
                       (str (some-> req :query pprint-str))] ; query as whitespace string should be available
                      [[match msg] (re-find #"^.+ :db.error/not-a-data-function (.+)$" error-str)] [:db.error/not-a-data-function msg]
                      [[match msg] (re-find #"^.+ :db.error/not-an-entity (.+)$" error-str)]
                      [:db.error/not-an-entity msg
                       "Hint: If this is a schema attribute, does it exist?
                       This can happen if you create a schema entity and then try to use it in the same transaction."]
                      [[match msg] (re-find #"^.+ :db.error/wrong-type-for-attribute (.+)$" error-str)] [:db.error/wrong-type-for-attribute msg]
                      [[match msg] (re-find #"^.+ :hyperfiddle.error/basis-stale (.+)$" error-str)] [:hyperfiddle.error/basis-stale msg]

                      ; It is the Clojure way.
                      [[match msg] (re-find #"^com.google.common.util.concurrent.UncheckedExecutionException: java.lang.IllegalArgumentException: (.+)$" error-str)] [:hyperfiddle.error/invalid-pull msg]
                      [[match msg] (re-find #"^.+ message: Unable to find data source: (.+)$" error-str)]
                      (let [expected (parse-query-element (some-> req :query) :in)]
                        [:hyperfiddle.error/query-arity
                         (str "Query argument missing: " msg " which corresponds with " expected ".\n"
                              "Hint: add a link/formula or edit the URL.")]))]
    (if-let [[ident error-msg human-hint] parsed-soup]
      (ex-info (str ident) {:ident ident
                            :error-msg error-msg
                            :human-hint human-hint})

      (ex-info (str :hyperfiddle.error/unrecognized)
               {:ident :hyperfiddle.error/unrecognized
                :error-msg error-str
                :human-hint (pr-str req)}))))

(defn- extract-tempid-lookup+ [internal-secure-db+]
  ; todo function should fall out with error-cleaning
  (if (exception/success? internal-secure-db+)
    (cats/fmap :id->tempid internal-secure-db+)
    (exception/failure (error-cleaner (cats/extract internal-secure-db+) nil))))

(defn extract-tempid-lookups [db-with-lookup pid]
  (->> (get @db-with-lookup pid)
       (map-values extract-tempid-lookup+)))


;;;;;;;;;;;;;;;
;; HYDRATION ;;
;;;;;;;;;;;;;;;

(defn max-arity [avar]
  (or (some->> avar meta :arglists
               (map (fn [args] (count (remove #{'&} args))))
               (apply max))
      0))

(defn eval-as-fexpr!
  "Like `clojure.core/eval`, but don't evaluate operands.
  https://en.wikipedia.org/wiki/Fexpr ."
  [[fn-sym & args]]
  (-> (find-var fn-sym)
      (deref)
      (apply args)))

(defn- get-db [getterf pid dbname]
  (:db (getterf dbname pid)))

(defn hydrate-request [domain get-secure-db-with+ [route pid :as F] ?subject]
  (either/branch-left
    ; Force exceptions here. How?
    (try-either
      (let [get-db' (partial get-db (comp exception/extract get-secure-db-with+) pid)]
        (binding [hf/*get-db* get-db'
                  hf/*subject* ?subject
                  ; get-db will realize the database, possibly throwing on a bad stage
                  ; which should not break the page here. See test
                  #_#_hf/*$* (silence! (do-result (get-db' "$")))
                  hf/*$*     (get-db' "$")
                  hf/*route* route
                  hf/*domain* domain]
          (with-bindings (hf/bindings domain)
            (let [[f & args]     route
                  defaults (apply hf/defaults (data/zipseq args (repeat (max-arity (find-var f)) nil)))]
              [(cons f defaults)
               (cons f (apply hf/view-defaults defaults))
               (eval-as-fexpr! (cons f defaults))])))))
    (fn [e] (either/left (error-cleaner e F)))))

;; F is [ƒ, pid].
;; F is a fiddle function evaluation in the context of a given branch.
(defn hydrate-requests [domain local-basis Fs partitions ?subject]
  {:pre [Fs (not-any? nil? Fs)]}
  (do/scope [`hydrate-requests Fs]
    (let [db-with-lookup (atom {})
          local-basis (into {} local-basis)                 ; :: ([dbname 1234]), but there are some duck type shenanigans happening
          get-secure-db-with+ (build-get-secure-db-with+ domain (constantly partitions) db-with-lookup local-basis ?subject)
          pulled-trees (->> Fs
                            (map #(hydrate-request domain get-secure-db-with+ % ?subject))
                            (doall))
          tempid-lookups (map-values #(map-values extract-tempid-lookup+ %) @db-with-lookup)]
      {:pulled-trees   pulled-trees
       :tempid-lookups tempid-lookups})))
