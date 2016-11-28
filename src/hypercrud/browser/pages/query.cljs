(ns hypercrud.browser.pages.query
  (:require [cljs.reader :as reader]
            [clojure.set :as set]
            [hypercrud.browser.links :as links]
            [hypercrud.browser.pages.entity :as entity]
            [hypercrud.client.core :as hc]
            [hypercrud.client.graph :as hc-g]
            [hypercrud.compile.eval :refer [eval]]
            [hypercrud.form.q-util :as q-util]
            [hypercrud.form.util :as form-util]
            [hypercrud.types :refer [->DbId ->DbVal ->Entity]]
            [hypercrud.ui.auto-control :refer [auto-control]]
            [hypercrud.ui.table :as table]
            [hypercrud.util :as util]))


(defn initial-entity [q holes-by-name params]
  (let [schema (util/map-values (fn [hole]
                                  {:db/valueType (-> hole :attribute/valueType :db/ident)
                                   :db/cardinality (-> hole :attribute/cardinality :db/ident)})
                                holes-by-name)
        dbid (->DbId -1 :query-hole-form)
        dbval (->DbVal :query-hole-form nil)
        data (->> (q-util/parse-holes q)
                  (map (juxt identity #(get params %)))
                  (into {:db/id dbid}))]
    (->Entity (hc-g/->DbGraph schema dbval nil {} {}) dbid data {})))


(defn show-results? [hole-names param-values]
  (set/subset? (set hole-names) (set (keys (into {} (remove (comp nil? val) param-values))))))


;; Why is this a hc/with - a fake entity?
;; Because we're faking a dynamic formfield entity, which needs to be attached to the real editor graph
(defn holes->field-tx [editor-graph form-dbid hole-names holes-by-name]
  ;todo these ids are scary
  (->> hole-names
       (map #(get holes-by-name %))
       (remove nil?)
       (mapcat (fn [{:keys [:hole/name
                            :field/prompt                   ;:field/query :field/label-prop
                            :attribute/valueType :attribute/cardinality] :as hole}]
                 (let [field-dbid (->DbId (+ 5555555 (-> hole :db/id .-id)) (-> editor-graph .-dbval .-conn-id))
                       attr-dbid (->DbId (+ 6555555 (-> hole :db/id .-id)) (-> editor-graph .-dbval .-conn-id))]
                   [[:db/add attr-dbid :attribute/ident name]
                    [:db/add attr-dbid :attribute/valueType (.-dbid valueType)]
                    [:db/add attr-dbid :attribute/cardinality (.-dbid cardinality)]
                    [:db/add field-dbid :field/prompt prompt]
                    ;[:db/add field-dbid :field/query (some-> query .-dbid)]
                    ;[:db/add field-dbid :field/label-prop label-prop]
                    [:db/add field-dbid :field/attribute attr-dbid]
                    [:db/add form-dbid :form/field field-dbid]])))))


(defn repeating-links [stage-tx! link result navigate-cmp param-ctx]
  (->> (:link/link link)
       (filter :link/repeating?)
       (mapv (fn [link]
               (let [param-ctx (merge param-ctx {:result result})
                     props (links/query-link stage-tx! link param-ctx)]
                 ^{:key (:db/id link)}
                 [navigate-cmp props (:link/prompt link)])))))


(defn non-repeating-links [stage-tx! link navigate-cmp param-ctx]
  (->> (:link/link link)
       (remove :link/repeating?)
       (map (fn [link]
              (let [props (links/query-link stage-tx! link param-ctx)]
                ^{:key (:db/id link)}
                [navigate-cmp props (:link/prompt link)])))))


(defn ui [cur editor-graph stage-tx! graph {find-elements :link/find-element query :link/query result-renderer-code :link/result-renderer
                                            :as link} params-map navigate-cmp param-ctx debug]
  (if-let [q (some-> (:query/value query) reader/read-string)]
    (let [{params :query-params create-new-find-elements :create-new-find-elements} params-map
          hole-names (q-util/parse-holes q)
          expanded-cur (cur [:expanded] {})
          holes-by-name (->> (:query/hole query)
                             (map (juxt :hole/name identity))
                             (into {}))
          entity-cur (cur [:holes] (initial-entity q holes-by-name params)) ;todo this needs to be hc/with
          entity @entity-cur
          dbhole-values (q-util/build-dbhole-lookup query)
          hole-lookup (merge (-> entity .-data (dissoc :db/id)) dbhole-values)
          dbval (get param-ctx :dbval)]
      #_(if-not (:link/query link))
      #_[entity/ui cur stage-tx! graph (first entities) form navigate-cmp]
      [:div
       #_[:pre (pr-str params)]                             ;; params are same information as the filled holes in this form below
       #_(let [stage-tx! (fn [tx]
                           (reset! entity-cur (reduce tx/merge-entity-and-stmt entity tx)))
               holes-form-dbid (->DbId -1 (-> editor-graph .-dbval .-conn-id))
               editor-graph (hc/with' editor-graph (holes->field-tx editor-graph holes-form-dbid hole-names holes-by-name))
               holes-form (hc/entity editor-graph holes-form-dbid)]
           [form/form graph entity holes-form expanded-cur stage-tx! navigate-cmp])
       (if-not (show-results? hole-names hole-lookup)       ;todo what if we have a user hole?
         [:div (str "Unfilled query holes" (pr-str hole-lookup))]
         (let [resultset (->> (let [resultset (hc/select graph (.-dbid query))]
                                (if (and (:query/single-result-as-entity? query) (= 0 (count resultset)))
                                  (let [local-result (mapv #(get create-new-find-elements (:find-element/name %))
                                                           find-elements)]
                                    [local-result])
                                  resultset))
                              (mapv (fn [result]
                                      (mapv #(hc/entity (hc/get-dbgraph graph dbval) %) result))))
               form-lookup (->> (mapv (juxt :find-element/name :find-element/form) find-elements)
                                (into {}))
               ordered-forms (->> (util/parse-query-element q :find)
                                  (mapv str)
                                  (mapv #(get form-lookup %)))]
           (if (:query/single-result-as-entity? query)
             (let [result (first resultset)]
               [:div
                [entity/ui cur stage-tx! graph result ordered-forms navigate-cmp]
                (->> (concat (repeating-links stage-tx! link result navigate-cmp param-ctx)
                             (non-repeating-links stage-tx! link navigate-cmp param-ctx))
                     (interpose " "))])
             [:div
              (let [repeating-links (->> (:link/link link)
                                         (filter :link/repeating?))]
                (if (empty? result-renderer-code)
                  ^{:key (hc/t graph)}
                  [table/table graph resultset ordered-forms repeating-links expanded-cur stage-tx! navigate-cmp nil]
                  (let [{result-renderer :value error :error} (eval result-renderer-code)]
                    (if error
                      [:div (pr-str error)]
                      [:div
                       [:ul
                        (->> resultset
                             (map (fn [result]
                                    (let [link-fn (fn [ident label]
                                                    (let [link (->> repeating-links
                                                                    (filter #(= ident (:link/ident %)))
                                                                    first)
                                                          param-ctx (merge param-ctx {:result result})
                                                          props (links/query-link stage-tx! link param-ctx)]
                                                      [navigate-cmp props label]))]
                                      [:li {:key (hash result)}
                                       (try
                                         (result-renderer graph link-fn result)
                                         (catch :default e (pr-str e)))]))))]]))))
              (interpose " " (non-repeating-links stage-tx! link navigate-cmp param-ctx))])))])
    [:div "Query record is incomplete"]))


(defn query [state editor-graph {find-elements :link/find-element query :link/query} params-map param-ctx]
  (let [{params :query-params create-new-find-elements :create-new-find-elements} params-map
        expanded-forms (get state :expanded nil)]
    (if-let [q (some-> (:query/value query) reader/read-string)]
      (let [hole-names (q-util/parse-holes q)
            holes-by-name (->> (:query/hole query)
                               (map (juxt :hole/name identity))
                               (into {}))
            ;; this is the new "param-ctx" - it is different - we already have the overridden
            ;; values, there is no need to eval the formulas in a ctx to get the values.
            param-values (-> (or (get state :holes)
                                 (initial-entity q holes-by-name params))
                             .-data
                             (dissoc :db/id))
            dbhole-values (q-util/build-dbhole-lookup query)
            hole-lookup (merge param-values dbhole-values)]
        (merge
          ;no fields are isComponent true or expanded, so we don't need to pass in a forms map
          (let [holes-form-dbid (->DbId -1 (-> editor-graph .-dbval .-conn-id))
                tx (holes->field-tx editor-graph holes-form-dbid hole-names holes-by-name)
                editor-graph (hc/with' editor-graph tx)
                holes-form (hc/entity editor-graph holes-form-dbid)]
            (form-util/form-option-queries holes-form nil q-util/build-params-from-formula param-ctx))

          (if (show-results? hole-names hole-lookup)
            (let [p-filler (fn [query formulas param-ctx]
                             (q-util/build-params (fn [hole-name]
                                                    (let [v (get hole-lookup hole-name)]
                                                      (if (instance? hypercrud.types.DbId v) (.-id v) v)))
                                                  query param-ctx))
                  dbval (get param-ctx :dbval)
                  query-for-form (fn [{find-name :find-element/name form :find-element/form :as find-element}]
                                   (merge
                                     (form-util/form-option-queries form expanded-forms p-filler param-ctx)
                                     (table/option-queries form p-filler param-ctx)
                                     {(.-dbid query) [q (p-filler query nil param-ctx)
                                                      {find-name [dbval (form-util/form-pull-exp form expanded-forms)]}]}))]
              (if (:query/single-result-as-entity? query)
                ; we can use nil for :link/formula and formulas because we know our p-filler doesn't use it
                (apply merge (map query-for-form find-elements))
                (table/query p-filler param-ctx query find-elements nil)))))))))
