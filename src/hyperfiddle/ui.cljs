(ns hyperfiddle.ui
  (:require-macros [hyperfiddle.ui :refer [-build-fiddle]])
  (:require
    [cats.core :refer [fmap]]
    [clojure.core.match :refer [match match*]]
    [clojure.string :as string]
    [contrib.css :refer [css css-slugify]]
    [contrib.data :refer [take-to unwrap]]
    [contrib.pprint :refer [pprint-str]]
    [contrib.reactive :as r]
    [contrib.reagent :refer [fragment]]
    [contrib.reactive-debug :refer [track-cmp]]
    [contrib.string :refer [memoized-safe-read-edn-string blank->nil or-str]]
    [contrib.ui]
    [contrib.ui.input]
    [contrib.ui.safe-render :refer [user-portal]]
    [hypercrud.browser.context :as context]
    [hypercrud.browser.core :as browser]
    [hypercrud.browser.link :as link]
    [hypercrud.ui.connection-color :refer [border-color]]
    [hypercrud.ui.control.link-controls]                    ; legacy
    [hypercrud.ui.error :as ui-error]
    [hyperfiddle.data :as data]
    [hyperfiddle.ui.api]
    [hyperfiddle.ui.controls :as controls]
    [hyperfiddle.ui.hyper-controls :refer [hyper-label hyper-select-head magic-new-body magic-new-head]]
    [hyperfiddle.ui.hacks]                                  ; exports
    [hyperfiddle.ui.link-impl :as ui-link :refer [anchors iframes]]
    [hyperfiddle.ui.select :refer [select]]
    [hyperfiddle.ui.sort :as sort]
    [hyperfiddle.ui.util :refer [eval-renderer-comp]]))


(defn attr-renderer [user-render-s]
  (let [user-render (eval-renderer-comp nil user-render-s)]
    (fn [val props ctx]
      ^{:key (hash user-render-s)}                          ; broken key user-render-s
      [user-portal (ui-error/error-comp ctx)
       [user-render val props ctx]])))

(defn ^:export control "this is a function, which returns component" [ctx] ; returns Func[(ref, props, ctx) => DOM]
  (let [attr @(context/hydrate-attribute ctx (:hypercrud.browser/attribute ctx))]
    (let [type (some-> attr :db/valueType :db/ident name keyword)
          cardinality (some-> attr :db/cardinality :db/ident name keyword)]
      (match* [type cardinality]
        [:boolean :one] controls/boolean
        [:keyword :one] controls/keyword
        [:string :one] controls/string
        [:long :one] controls/long
        [:instant :one] controls/instant
        [:ref :one] controls/dbid                           ; nested form
        [:ref :many] (constantly [:noscript]) #_edn-many    ; nested table
        [_ :one] controls/edn
        [_ :many] controls/edn-many))))

(declare result)

(defn control+ [val props ctx]
  (fragment
    [(control ctx) val props ctx]
    [anchors (:hypercrud.browser/path ctx) props ctx]       ; Order sensitive, here be floats
    [iframes (:hypercrud.browser/path ctx) props ctx]))

(defn links-only+ [val props ctx]
  (fragment
    [anchors (:hypercrud.browser/path ctx) props ctx]       ; Order sensitive, here be floats
    [iframes (:hypercrud.browser/path ctx) props ctx]))

(defn result+ [val props ctx]
  (fragment
    [result ctx]                                            ; flipped args :(
    [anchors (:hypercrud.browser/path ctx) props ctx]       ; Order sensitive, here be floats
    [iframes (:hypercrud.browser/path ctx) props ctx]))

(defn select+ [val props ctx]
  (fragment
    [anchors (:hypercrud.browser/path ctx) props ctx ui-link/options-processor] ; Order sensitive, here be floats
    [select props ctx]
    [iframes (:hypercrud.browser/path ctx) props ctx ui-link/options-processor]))

(defn ^:export hyper-control "Handles labels too because we show links there." ; CTX is done after here. props and val only. But recursion needs to look again.
  [ctx]
  {:post [%]}
  (let [head-or-body (->> (:hypercrud.browser/path ctx) (reverse) (take-to (comp not #{:head :body})) (last)) ; todo head/body attr collision
        rels (->> (:hypercrud.browser/links ctx)
                  (r/fmap (fn [links]
                            (->> links
                                 (filter (r/partial ui-link/draw-link? (:hypercrud.browser/path ctx)))
                                 (map :link/rel)
                                 (into #{})))))
        segment (last (:hypercrud.browser/path ctx))
        segment-type (context/segment-type segment)
        child-fields (not (some->> (:hypercrud.browser/fields ctx) (r/fmap nil?) deref))]
    (match* [head-or-body segment-type segment child-fields @rels]
      ;[:head _ true] hyper-select-head
      [:head :attribute '* _ _] magic-new-head
      [:head _ _ _ _] hyper-label
      [:body :attribute '* _ _] magic-new-body
      [:body :attribute _ _ (true :<< #(contains? % :options))] select+
      [:body :attribute _ true _] result+
      [:body :attribute _ false _] (or (some-> @(context/hydrate-attribute ctx (:hypercrud.browser/attribute ctx))
                                               :attribute/renderer blank->nil attr-renderer)
                                       control+)
      [:body _ _ true _] links-only+                        ; what?
      [:body _ _ false _] controls/string                   ; aggregate, entity, what else?
      )))

(defn ^:export semantic-css [ctx]
  ; Include the fiddle level ident css.
  ; Semantic css needs to be prefixed with - to avoid collisions. todo
  (->> (:hypercrud.browser/path ctx)
       (remove #{:head :body})
       (concat
         ["hyperfiddle"
          (:hypercrud.browser/source-symbol ctx)            ; color
          (name (context/segment-type (last (:hypercrud.browser/path ctx))))
          (or (some #{:head} (:hypercrud.browser/path ctx)) ; could be first nested in a body
              (some #{:body} (:hypercrud.browser/path ctx)))
          (->> (:hypercrud.browser/path ctx)                ; generate a unique selector for each location
               (remove #{:head :body})
               (map css-slugify)
               (string/join "/"))]
         (let [attr (context/hydrate-attribute ctx (:hypercrud.browser/attribute ctx))]
           [@(r/cursor attr [:db/valueType :db/ident])
            @(r/cursor attr [:attribute/renderer])  #_label/fqn->name
            @(r/cursor attr [:db/cardinality :db/ident])
            (some-> @(r/cursor attr [:db/isComponent]) (if :component))]))
       (map css-slugify)))

(defn ^:export value "Relation level value renderer. Works in forms and lists but not tables (which need head/body structure).
User renderers should not be exposed to the reaction."
  ; Path should be optional, for disambiguation only. Naked is an error
  [relative-path ctx ?f & [props]]                          ; ?f :: (ref, props, ctx) => DOM
  (let [ctx (context/focus ctx relative-path)
        props (update props :class css (semantic-css ctx))]
    (if ?f
      [?f @(:hypercrud.browser/data ctx) props ctx]
      [(hyper-control ctx) @(:hypercrud.browser/data ctx) props ctx])))

(defn ^:export link "Relation level link renderer. Works in forms and lists but not tables."
  [rel relative-path ctx ?content & [props]]                ; path should be optional, for disambiguation only. Naked can be hard-linked in markdown?
  (let [ctx (context/focus ctx relative-path)
        link @(r/track link/rel->link rel ctx)]
    ;(assert (not render-inline?)) -- :new-fiddle is render-inline. The nav cmp has to sort this out before this unifies.
    [(:navigate-cmp ctx) (merge (link/build-link-props link ctx) props) (or ?content (name (:link/rel link))) (:class props)]))

(defn ^:export browse "Relation level browse. Works in forms and lists but not tables."
  [rel relative-path ctx ?content & [props]]                ; path should be optional, for disambiguation only. Naked can be hard-linked in markdown?
  (let [ctx (context/focus ctx relative-path)
        link @(r/track link/rel->link rel ctx)]
    ;(assert render-inline?)
    [browser/ui link
     (if ?content (assoc ctx :user-renderer ?content #_(if ?content #(apply ?content %1 %2 %3 %4 args))) ctx)
     (:class props)
     (dissoc props :class :children nil)]))

(defn form-field "Form fields are label AND value. Table fields are label OR value."
  [hyper-control relative-path ctx ?f props]                ; ?f :: (val props ctx) => DOM
  (let [state (r/atom {:hyperfiddle.ui.form/magic-new-a nil})]
    (fn [hyper-control relative-path ctx ?f props]
      (let [ctx (assoc ctx :hyperfiddle.ui.form/state state)
            ; we want the wrapper div to have the :body styles, so careful not to pollute the head ctx with :body
            body-ctx (context/focus ctx (cons :body relative-path))
            head-ctx (context/focus ctx (cons :head relative-path))
            props (update props :class css (semantic-css body-ctx))]
        ; It is the driver-fn's job to elide this field if it will be empty
        [:div {:class (css "field" (:class props))
               :style {:border-color (border-color body-ctx)}}
         ^{:key :form-head}                                 ; Why is the data in the head?
         [(or (:label-fn props) (hyper-control head-ctx)) nil props head-ctx]
         ^{:key :form-body}
         [:div
          (if ?f
            [?f @(:hypercrud.browser/data body-ctx) props body-ctx]
            [(hyper-control body-ctx) @(:hypercrud.browser/data body-ctx) props body-ctx])]]))))

(defn table-field "Form fields are label AND value. Table fields are label OR value."
  [hyper-control relative-path ctx ?f props]                ; ?f :: (val props ctx) => DOM
  (let [head-or-body (last (:hypercrud.browser/path ctx))   ; this is ugly and not uniform with form-field
        ctx (context/focus ctx relative-path)
        props (update props :class css (semantic-css ctx))]
    (case head-or-body
      :head [:th {:class (css "field" (:class props)
                              (when (sort/sortable? ctx) "sortable") ; hoist
                              (some-> (sort/sort-direction relative-path ctx) name)) ; hoist
                  :style {:background-color (border-color ctx)}
                  :on-click (r/partial sort/toggle-sort! relative-path ctx)}
             ; Use f as the label control also, because there is hypermedia up there
             [(or (:label-fn props) (hyper-control ctx)) nil props ctx]]
      :body [:td {:class (css "field" (:class props) "truncate")
                  :style {:border-color (when (:hypercrud.browser/source-symbol ctx) (border-color ctx))}}
             (if ?f
               [?f @(:hypercrud.browser/data ctx) props ctx]
               [(hyper-control ctx) @(:hypercrud.browser/data ctx) props ctx])])))

; (defmulti field ::layout)
(defn ^:export field "Works in a form or table context. Draws label and/or value."
  [relative-path ctx & [?f props]]                          ; ?f :: (ref, props, ctx) => DOM
  (let [is-magic-new (= '* (last relative-path))]
    (case (:hyperfiddle.ui/layout ctx)
      :hyperfiddle.ui.layout/table (when-not is-magic-new
                                     ^{:key (str relative-path)}
                                     [table-field hyper-control relative-path ctx ?f props])
      (let [magic-new-key (when is-magic-new
                            (let [ctx (context/focus ctx (cons :body relative-path))]
                              ; guard against crashes for nil data
                              (hash (some->> ctx :hypercrud.browser/parent :hypercrud.browser/data (r/fmap keys) deref))))]
        ^{:key (str relative-path magic-new-key #_"reset magic new state")}
        [form-field hyper-control relative-path ctx ?f props]))))

(defn ^:export table "Semantic table"
  [form ctx & [props]]
  (let [sort-col (r/atom nil)
        sort (fn [v] (hyperfiddle.ui.sort/sort-fn v sort-col))]
    (fn [form ctx & [props]]
      (let [ctx (assoc ctx ::sort/sort-col sort-col
                           ::layout :hyperfiddle.ui.layout/table)]
        [:table (update props :class css "ui-table" "unp" (semantic-css ctx))
         (let [ctx (context/focus ctx [:head])]
           (->> (form ctx) (into [:thead])))                ; strict
         (->> (:hypercrud.browser/data ctx)
              (r/fmap sort)
              (r/unsequence data/relation-keyfn)            ; todo support nested tables
              (map (fn [[relation k]]
                     (->> (form (context/body ctx relation))
                          (into ^{:key k} [:tr]))))         ; strict
              (into [:tbody]))]))))

(defn hint [{:keys [hypercrud.browser/fiddle
                    hypercrud.browser/result]}]
  (if (and (-> (:fiddle/type @fiddle) (= :entity))
           (nil? (:db/id @result)))
    [:div.alert.alert-warning "Warning: invalid route (d/pull requires an entity argument). To add a tempid entity to the URL, click here: "
     [:a {:href "~entity('$','tempid')"} [:code "~entity('$','tempid')"]] "."]))

(letfn [(field-with-props [props relative-path ctx] (field relative-path ctx nil props))]
  (defn ^:export result "Default result renderer. Invoked as fn, returns seq-hiccup, hiccup or
nil. call site must wrap with a Reagent component"
    [ctx & [props]]
    ; focus should probably get called here. What about the request side?
    (fragment
      (hint ctx)
      (condp = (:hypercrud.browser/data-cardinality ctx)
        :db.cardinality/one (let [ctx (assoc ctx ::layout :hyperfiddle.ui.layout/block)
                                  key (-> (data/relation-keyfn @(:hypercrud.browser/data ctx)) str keyword)]
                              (apply fragment key (data/form (r/partial field-with-props props) ctx)))
        :db.cardinality/many [table (r/partial data/form (r/partial field-with-props props)) ctx props]
        ; blank fiddles
        nil))))

(def ^:dynamic markdown)                                    ; this should be hf-contrib or something

(def ^:export fiddle (-build-fiddle))

(defn ^:export fiddle-xray [ctx class]
  (let [{:keys [:hypercrud.browser/fiddle
                #_:hypercrud.browser/result]} ctx]
    [:div {:class class}
     [:h3 (pr-str (:route ctx)) #_(some-> @fiddle :fiddle/ident str)]
     (result ctx)]))

(letfn [(render-edn [data]
          (let [edn-str (-> (hyperfiddle.ui.hacks/pull-soup->tree data)
                            (pprint-str 160))]
            [contrib.ui/code edn-str #() {:read-only true}]))]
  (defn ^:export fiddle-api [ctx class]
    (let [data (hyperfiddle.ui.api/api-data ctx)]
      [:div.hyperfiddle.display-mode-api {:class class}
       [:h3
        [:dl
         [:dt "route"] [:dd (pr-str (:route ctx))]]]
       (render-edn (get data (:route ctx)))
       (->> (dissoc data (:route ctx))
            (map (fn [[route result]]
                   ^{:key (str (hash route))}
                   [:div
                    [:dl [:dt "route"] [:dd (pr-str route)]]
                    (render-edn result)])))])))

(defn ^:export img [val props ctx]
  [:img (merge props {:src val})])
