(ns hyperfiddle.ui.select
  (:require
    [cats.context :refer-macros [with-context]]
    [cats.core :as cats :refer [fmap mlet return]]
    [cats.monad.either :as either]
    [contrib.reactive :as r]
    [contrib.try$ :refer [try-either]]
    [hypercrud.browser.field :as field]
    [hypercrud.types.Entity :refer [entity?]]
    [hyperfiddle.data :as data]
    [hyperfiddle.ui.controls :as controls]
    [contrib.eval]))

(defn field-label [v]
  (if (instance? cljs.core/Keyword v)
    (name v)                                                ; A sensible default for userland whose idents usually share a long namespace.
    (str v)))

(defn option-label [data ctx]
  (->> (map (fn [data field]
              (->> (::field/children field)
                   (mapv (fn [{f ::field/get-value}]
                           (field-label (f data))))))
            data
            @(r/fmap ::field/children (:hypercrud.browser/field ctx)))
       (apply concat)
       (interpose ", ")
       (remove nil?)
       (apply str)))

(defn select-anchor-renderer' [props option-props ctx]
  ; hack in the selected value if we don't have options hydrated?
  ; Can't, since we only have the #DbId hydrated, and it gets complicated with relaton vs entity etc
  (let [no-options? @(r/fmap empty? (:hypercrud.browser/data ctx))
        props (-> props
                  (update :on-change (fn [on-change]
                                       (fn [e]
                                         (let [select-value (.-target.value e)
                                               id (when (not= "" select-value)
                                                    (let [id (js/parseInt select-value 10)]
                                                      (if (< id 0) (str id) id)))]
                                           (on-change id)))))
                  ; Don't disable :select if there are options, we may want to see them. Make it look :disabled but allow the click
                  (update :disabled #(or % no-options?))
                  (update :class #(str % (if (:disabled option-props) " disabled"))))
        label-fn (contrib.eval/ensure-fn (:option-label props option-label))
        id-fn (fn [relation]
                (let [fe (first relation)]
                  (if (or (entity? fe) (map? fe))           ; todo inspect datalog find-element
                    (:db/id fe)
                    fe)))]
    [:select.ui (dissoc props :option-label)
     ; .ui is because options are an iframe and need the pink box
     (conj
       (->> @(:hypercrud.browser/data ctx)
            (mapv (juxt id-fn #(label-fn % ctx)))
            (sort-by second)
            (map (fn [[id label]]
                   [:option (assoc option-props :key (str id) :value id) label])))
       [:option (assoc option-props :key :blank :value "") "--"])]))

(defn select-error-cmp [msg]
  [:span msg])

(defn select-anchor-renderer [props option-props ctx]
  (case @(r/cursor (:hypercrud.browser/fiddle ctx) [:fiddle/type])
    :entity [select-error-cmp "Only fiddle type `query` is supported for select options"]
    :blank [select-error-cmp "Only fiddle type `query` is supported for select options"]
    :query (if (= :db.cardinality/many @(r/fmap ::field/cardinality (:hypercrud.browser/field ctx)))
             [select-anchor-renderer' props option-props ctx]
             [select-error-cmp "Tuples and scalars are unsupported for select options. Please fix your options query to return a relation or collection"])
    ; default
    [select-error-cmp "Only fiddle type `query` is supported for select options"]))

(defn compute-disabled [ctx props]
  (let [entity (get-in ctx [:hypercrud.browser/parent :hypercrud.browser/data])] ; how can this be loading??
    (or (boolean (:read-only props))
        @(r/fmap nil? entity)                               ; no value at all
        (not @(r/fmap controls/writable-entity? entity)))))

(let [dom-value (fn [value]                                 ; nil, kw or eid
                  (if (nil? value) "" (str (:db/id value))))]
  (defn select
    ([props ctx]                                            ; legacy auto interface
     (select (data/select+ ctx :options nil) props ctx))
    ([options-ref+ props ctx]
     "This arity should take a selector string (class) instead of Right[Reaction[Link]], blocked on removing path backdoor"
     {:pre [options-ref+ ctx]}
     (-> (mlet [options-ref options-ref+]
           (return
             (let [default-props {:on-change (r/partial controls/entity-change! ctx)}
                   props (-> (merge default-props props)
                             (assoc :value @(r/fmap dom-value (:hypercrud.browser/data ctx))))
                   props (-> (select-keys props [:class])
                             (assoc :user-renderer (r/partial select-anchor-renderer props {:disabled (compute-disabled ctx props)})))
                   ctx (assoc ctx
                         :hypercrud.ui/display-mode (r/track identity :hypercrud.browser.browser-ui/user))]
               [hyperfiddle.ui/ui-from-link options-ref ctx props])))
         (either/branch select-error-cmp identity)))))
