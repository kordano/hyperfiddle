(ns hypercrud.ui.form
  (:require [contrib.css :refer [css-slugify classes]]
            [contrib.data :refer [kwargs]]
            [contrib.reactive :as r]
            [contrib.reagent :refer [fragment]]
            [hypercrud.browser.context :as context]
            [hypercrud.browser.link :as link]
            [hypercrud.ui.auto-control :refer [auto-control' control-props]]
            [hypercrud.ui.connection-color :as connection-color]
            [hypercrud.ui.control.link-controls :as link-controls]
            [contrib.ui.input :as input]
            [hypercrud.ui.label :refer [label]]))


(defn ui-block-border-wrap [ctx class & children]
  [:div {:class (classes class "hyperfiddle-form-cell" (-> ctx :hypercrud.browser/attribute str css-slugify))
         :style {:border-color (connection-color/connection-color ctx)}}
   (apply fragment :_ children)])

(defn new-field-state-container [ctx]
  (let [attr-ident (r/atom nil)]
    (fn [ctx]
      (ui-block-border-wrap
        ctx "field"
        [:div (let [on-change! #(reset! attr-ident %)]
                [input/keyword-input* @attr-ident on-change! {:placeholder ":task/title"}])]
        (let [on-change! #(let [tx [[:db/add (:db/id @(:cell-data ctx)) @attr-ident %]]]
                            ; todo cardinality many
                            ((:user-with! ctx) tx))
              #_#_props (if (nil? @attr-ident) {:read-only true})]
          [input/edn-input* nil on-change! {:placeholder (pr-str "mow the lawn")}])))))

(defn new-field [ctx]
  ^{:key (hash (keys @(:cell-data ctx)))}
  [new-field-state-container ctx])

(defn form-cell [control ctx & [class]]              ; safe to return nil or seq
  (let [path [(:fe-pos ctx) (:hypercrud.browser/attribute ctx)]]
    (ui-block-border-wrap
      ctx (str class " field")
      [:div
       [label ctx]
       (link-controls/anchors path false ctx link/options-processor)
       (link-controls/iframes path false ctx link/options-processor)]
      ; todo unsafe execution of user code: control
      [control (control-props ctx) ctx])))

(defn Cell
  ([ctx] (Cell nil ctx))
  ([?f ctx & args]                                          ; fiddle-src wants to fallback by passing nil here explicitly
   (assert @(:hypercrud.ui/display-mode ctx))
   (let [props (kwargs args)]
     [form-cell (or ?f (auto-control' ctx)) ctx (:class props)])))

(defn Entity [ctx]
  (let [path [(:fe-pos ctx)]]
    (concat
      (link-controls/anchors path false ctx :class "hyperfiddle-link-entity-independent")
      (let [ctx (context/cell-data ctx)]
        (concat
          (conj
            (->> (r/cursor (:hypercrud.browser/find-element ctx) [:fields])
                 (r/unsequence :id)
                 (mapv (fn [[field id]]
                         ; unify with context/relation-path then remove
                         (let [field @field
                               ctx (as-> (context/field ctx field) $
                                         (context/value $ (r/fmap (:cell-data->value field) (:cell-data ctx)))
                                         (if (or (nil? (:attribute field)) (= (:attribute field) :db/id))
                                           (assoc $ :read-only (r/constantly true))
                                           $))]
                           ^{:key id}
                           [Cell ctx]))))
            (if @(r/cursor (:hypercrud.browser/find-element ctx) [:splat?])
              ^{:key :new-field}
              [new-field ctx]))
          (link-controls/anchors path true ctx :class "hyperfiddle-link-entity-dependent")
          (link-controls/iframes path true ctx)))
      (link-controls/iframes path false ctx))))

(defn Relation [ctx]
  ; No wrapper div; it limits layout e.g. floating. The pyramid mapcats out to a flat list of dom elements that comprise the form
  ; This is not compatible with hiccup syntax; this is a fn
  (let [ctx (assoc ctx :layout (:layout ctx :block))]       ; first point in time we know we're a form? can this be removed?
    (->> (r/unsequence (:hypercrud.browser/ordered-fes ctx))
         (mapcat (fn [[fe i]]
                   (Entity (context/find-element ctx i)))))))
