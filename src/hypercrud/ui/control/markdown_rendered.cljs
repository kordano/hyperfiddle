(ns hypercrud.ui.control.markdown-rendered
  (:require [contrib.css :refer [css-slugify classes]]
            [contrib.data :refer [map-values unwrap or-str]]
            [contrib.reagent :refer [fragment]]
            [contrib.reactive :as r]
            [contrib.string :refer [memoized-safe-read-edn-string]]
            [hyperfiddle.core :refer [read-eval-with-bindings]]
            [hypercrud.browser.context :as context]
            [hypercrud.ui.control.code]
            [goog.object]
            [reagent.core :as reagent]))


(declare remarkInstance)

; Reagent + react-context:
; https://github.com/reagent-project/reagent/commit/a8ec0d219bbd507f51a4d9276c4a1dcc020245af

(def markdown
  ; remark creates react components which don't evaluate in this stack frame
  ; so dynamic scope is not helpful to communicate values to remark plugins
  (reagent/create-class
    {:reagent-render
     (fn [value & [?ctx]]
       (when (and (string? value) (not (empty? value)))
         (-> remarkInstance (.processSync value {"commonmark" true}) .-contents)))

     :get-child-context
     (fn []
       (this-as this
         (let [[_ value ?ctx] (reagent/argv this)]
           #js {:ctx ?ctx})))

     :child-context-types #js {:ctx js/propTypes.object}}))

(defn md-extension [f]
  (reagent/create-class
    {:context-types #js {:ctx js/propTypes.object}
     :reagent-render (fn [{:keys [content argument] :as props}]
                       (let [ctx (goog.object/get (.-context (reagent/current-component)) "ctx")
                             content (if-not (= content "undefined") content)
                             argument (if-not (= argument "undefined") argument)]
                         (f content argument (dissoc props :content :argument) ctx)))}))


(declare markdown-relation)

(defn code-editor-wrap-argv [content argument props ctx]
  [hypercrud.ui.control.code/code* content #() props])

#_(fragment k (unwrap (read-eval-with-bindings content (context/relation ctx relation))))

(defn eval [content argument props ctx]
  (unwrap (read-eval-with-bindings content ctx)))

(defn browse [content argument props ctx]
  (let [kwargs (flatten (seq props))
        [_ srel spath] (re-find #"([^ ]*) ?(.*)" argument)
        rel (unwrap (memoized-safe-read-edn-string srel))
        path (unwrap (memoized-safe-read-edn-string (str "[" spath "]")))]
    (apply (:browse ctx) rel path ctx kwargs)))

(defn anchor [content argument props ctx]
  (let [kwargs (flatten (seq props))
        [_ srel spath] (re-find #"([^ ]*) ?(.*)" argument)
        rel (unwrap (memoized-safe-read-edn-string srel))
        path (unwrap (memoized-safe-read-edn-string (str "[" spath "]")))
        ; https://github.com/medfreeman/remark-generic-extensions/issues/45
        label (or-str content srel)]
    (apply (:anchor ctx) rel path ctx label kwargs)))

(defn cell [content argument props ctx]
  (let [kwargs (flatten (seq props))
        path (into [true] (unwrap (memoized-safe-read-edn-string (str "[" argument "]"))))]
    (apply (:cell ctx) path ctx kwargs)))

(defn ^:deprecated -table [content argument {:keys [class] :as props} ctx]
  (hypercrud.ui.table/Table ctx))

(defn result [content argument {:keys [class] :as props} ctx]
  (let [f (unwrap (read-eval-with-bindings content))]
    [:div.why
     (hypercrud.ui.result/result ctx f)]))

(letfn [(keyfn [relation] (hash (map #(or (:db/id %) %) relation)))]
  (defn list- [content argument {:keys [class] :as props} ctx]
    [:div {:class (classes class)}
     (->> (:relations ctx)
          (r/unsequence keyfn)
          (map (fn [[relation k]]
                 ^{:key k} [markdown-relation k content (context/relation ctx relation)]))
          (doall))]))

(defn value [content argument props ctx]
  (let [content (unwrap (read-eval-with-bindings content))
        path (into [true] (unwrap (memoized-safe-read-edn-string (str "[" argument "]"))))]
    (fragment path ((:value ctx) path ctx content))))

(def whitelist-reagent
  ; Div is not needed, use it with block syntax and it hits React.createElement and works
  ; see https://github.com/medfreeman/remark-generic-extensions/issues/30
  {"span" (fn [content argument props ctx]
            [:span (dissoc props :children) content])
   "p" (fn [content argument props ctx]
         [:div (-> props
                   (dissoc :children)
                   (update :class #(classes "p" %)))
          (:children props)])
   "CodeEditor" code-editor-wrap-argv
   "block" (fn [content argument props ctx]
             [:div props [markdown content]])
   "cljs" eval
   "browse" browse
   "anchor" anchor
   "cell" cell
   "table" -table
   "result" result
   "list" list-
   "value" value
   ; relations ; table renderer with docs above and below
   ; relation ? (for setting a title and docs, and then rendering the form by default links and all. Maybe can filter the relation down to a cell path?

   })

; https://github.com/medfreeman/remark-generic-extensions
; https://github.com/zestedesavoir/zmarkdown/tree/master/packages/remark-grid-tables
; https://github.com/zestedesavoir/zmarkdown/tree/master/packages/remark-custom-blocks

(def remarkInstance (-> (js/remark)
                        ;(.use js/remarkCustomBlocks (clj->js {"some" "a"}))
                        (.use js/remarkGenericExtensions
                              (clj->js
                                {"elements" (into {} (map vector (keys whitelist-reagent) (repeat {"html" {"properties" {"content" "::content::" "argument" "::argument::"}}})))}))
                        (.use js/remarkGridTables)
                        (.use js/remarkReact
                              (clj->js
                                {"sanitize" false
                                 "remarkReactComponents" (->> whitelist-reagent
                                                              (map-values
                                                                (comp reagent/reactify-component
                                                                      md-extension)))}))))


; Todo; remove div.markdown; that should be default and style the inverse.
(defn markdown-rendered* [md & [?ctx class]]
  [:div {:class (classes "markdown" class)}
   [markdown md ?ctx]])

(defn markdown-relation [k content ctx & class]
  ^{:key k} [markdown-rendered* content ctx class])
