(ns hyperfiddle.ide.fiddles.fiddle-links.renderer
  (:require
    [cats.core :refer [mlet return]]
    [cats.monad.either :as either]
    [contrib.reactive :as r]
    [contrib.reagent :refer [fragment]]
    [hypercrud.browser.base :as base]
    [hypercrud.browser.context :as context]
    [hypercrud.browser.system-link :refer [system-link?]]
    [hypercrud.client.core :as hc]
    [hyperfiddle.data :as data]
    [hyperfiddle.ui :refer [hyper-control field table select+ link]]
    [hyperfiddle.ui.select :refer [select select-error-cmp]]))


(def editable-if-shadowed?
  #{:link/disabled? :link/render-inline? :link/fiddle :link/formula :link/tx-fn :hypercrud/props})

(defn read-only? [ctx]
  (if (:hypercrud.browser/data ctx)                         ; be robust to being called on labels
    (let [entity (get-in ctx [:hypercrud.browser/parent :hypercrud.browser/data])
          sys? (system-link? @(r/fmap :db/id entity))
          shadow? @(r/fmap :hypercrud/sys? entity)]
      (or sys? (and shadow? (not (editable-if-shadowed? (:hypercrud.browser/attribute ctx))))))))

(defn read-only-cell [val props ctx]
  ; Need to delay until we have the value ctx to compute this, which means its a value renderer not a field prop
  (let [props (assoc props :read-only (read-only? ctx))]
    [(hyper-control ctx) val props ctx]))

(defn link-fiddle [val props ctx]
  (fragment
    (link :hyperfiddle/new "fiddle" ctx)
    (let [props (assoc props :read-only (read-only? ctx))]
      [select+ val props ctx])))

(letfn [(remove-children [field] (dissoc field :hypercrud.browser.field/children))]
  (defn hf-live-link-fiddle [val props ctx]
    (let [ctx (-> ctx
                  (update :hypercrud.browser/field #(r/fmap remove-children %))
                  (assoc :hypercrud.browser/fields (r/track identity nil)))
          props (assoc props :read-only (read-only? ctx))]
      ; Hacks because hf-live is not yet modeled in the fiddle-graph, we hardcode knowledge of the IDE fiddle-graph instead
      (-> (mlet [req (base/meta-request-for-fiddle (assoc ctx
                                                     :route (hyperfiddle.ide/ide-fiddle-route (context/target-route ctx) ctx)
                                                     :branch nil))
                 topnav-fiddle @(hc/hydrate (:peer ctx) nil req) #_"todo tighter reactivity"]
            (return
              (let [ctx (merge ctx {:hypercrud.browser/links (r/track identity (:fiddle/links topnav-fiddle))})]
                [select (data/select+ ctx :options (:options props)) props ctx])))
          (either/branch select-error-cmp identity))
      )))

(defn renderer [ctx & [embed-mode]]
  [table
   #_(partial form (fn [path ctx ?f & args] (field path ctx ?f :read-only (read-only? ctx))))
   (fn [ctx]
     [(when-not embed-mode (field [:link/disabled?] ctx read-only-cell))
      (field [:link/rel] ctx read-only-cell)
      (field [:link/path] ctx read-only-cell)
      (field [:link/class] ctx read-only-cell)
      (field [:link/render-inline?] ctx read-only-cell)
      (field [:link/fiddle] ctx (if embed-mode hf-live-link-fiddle link-fiddle) {:options "fiddle-options"})
      (when-not embed-mode (field [:link/create?] ctx read-only-cell))
      (when-not embed-mode (field [:link/managed?] ctx read-only-cell))
      (field [:link/formula] ctx read-only-cell)
      (when-not embed-mode (field [:link/tx-fn] ctx read-only-cell))
      (when-not embed-mode (field [:hypercrud/props] ctx read-only-cell))
      (when-not embed-mode (field [] ctx
                                  (fn [val props ctx]
                                    (link :hyperfiddle/remove "link" ctx)
                                    )))])
   ctx])
