(ns hypercrud.browser.browser-request
  (:require [cats.core :as cats :refer [mlet]]
            [cats.monad.either :as either]
            [hypercrud.browser.base :as base]
            [hypercrud.browser.context :as context]
            [hypercrud.browser.link :as link]
            [hypercrud.browser.popovers :as popovers]
            [hypercrud.browser.routing :as routing]
            [hypercrud.client.schema :as schema-util]
            [hypercrud.util.core :refer [unwrap]]
            [hypercrud.util.non-fatal :refer [try-catch-non-fatal try-either]]
            [hypercrud.util.reactive :as reactive]
            [hyperfiddle.runtime :as runtime]
            [taoensso.timbre :as timbre]))


(declare request-from-route)
(declare request-from-link)

(defn recurse-request [link ctx]
  (if (:link/managed? link)
    (let [route' (routing/build-route' link ctx)
          popover-id (popovers/popover-id link ctx)]
      (if @(runtime/state (:peer ctx) [::runtime/partitions (:branch ctx) :popovers popover-id])
        ; if the anchor IS a popover, we need to run the same logic as link/managed-popover-body
        ; the ctx needs to be updated (branched, etc), but NOT BEFORE determining the route
        ; that MUST happen in the parent context
        (let [ctx (-> ctx
                      (assoc :branch (popovers/branch ctx link))
                      (context/clean))]
          (either/branch route'
                         (constantly nil)
                         #(request-from-route % ctx)))))
    ; if the anchor IS NOT a popover, this should be the same logic as widget/render-inline-anchors
    (request-from-link link ctx)))

(defn cell-dependent-requests [ctx]
  (let [ctx (context/cell-data ctx)]
    (concat
      (->> @(:hypercrud.browser/links ctx)
           (filter :link/dependent?)
           (filter (link/same-path-as? [(:fe-pos ctx)]))
           (mapcat #(recurse-request % ctx)))
      (->> @(reactive/cursor (:hypercrud.browser/find-element ctx) [:fields])
           (mapcat (fn [field]
                     (let [ctx (-> (context/field ctx field)
                                   (context/value (reactive/fmap (:cell-data->value field) (:cell-data ctx))))]
                       (->> @(:hypercrud.browser/links ctx)
                            (filter :link/dependent?)
                            (filter (link/same-path-as? [(:fe-pos ctx) (:hypercrud.browser/attribute ctx)]))
                            (mapcat #(recurse-request % ctx))))))))))

(defn form-requests [ctx]                          ; ui equivalent of form
  (->> (reactive/unsequence (:hypercrud.browser/ordered-fes ctx))
       (mapcat (fn [[fe i]]
                 (cell-dependent-requests (context/find-element ctx i))))))

(defn table-requests [ctx]                        ; ui equivalent of table
  ; the request side does NOT need the cursors to be equiv between loops
  (->> (reactive/unsequence (:relations ctx))
       (mapcat (fn [[relation i]]
                 (form-requests (context/relation ctx relation))))))

(defn- filter-inline [links] (filter :link/render-inline? links))

(defn fiddle-dependent-requests [ctx]
  ; at this point we only care about inline links
  (let [ctx (update ctx :hypercrud.browser/links (partial reactive/fmap filter-inline))]
    (concat
      (->> @(:hypercrud.browser/links ctx)
           (remove :link/dependent?)
           (filter (link/same-path-as? []))
           (mapcat #(recurse-request % ctx)))
      (->> (reactive/unsequence (:hypercrud.browser/ordered-fes ctx)) ; might have empty results-- DJG Dont know what this prior comment means?
           (mapcat (fn [[fe i]]
                     (let [ctx (context/find-element ctx i)
                           fe-pos (:fe-pos ctx)]
                       (concat
                         (->> @(:hypercrud.browser/links ctx)
                              (remove :link/dependent?)
                              (filter (link/same-path-as? [fe-pos]))
                              (mapcat #(recurse-request % ctx)))
                         (->> @(reactive/cursor (:hypercrud.browser/find-element ctx) [:fields])
                              (mapcat (fn [field]
                                        (let [ctx (context/field ctx field)]
                                          (->> @(:hypercrud.browser/links ctx)
                                               (remove :link/dependent?)
                                               (filter (link/same-path-as? [fe-pos (:hypercrud.browser/attribute ctx)]))
                                               (mapcat #(recurse-request % ctx))))))))))))
      (if-let [ctx (unwrap (context/with-relations ctx))]
        (if (:relations ctx)
          (table-requests ctx)
          (form-requests ctx))))))

(defn f-mode-config []
  {:from-ctx :user-request
   :from-fiddle (fn [fiddle] @(reactive/cursor fiddle [:fiddle/request]))
   :with-user-fn (fn [user-fn]
                   (fn [ctx]
                     ; todo report invocation errors back to the user
                     ; user-fn HAS to return a seqable value, we want to throw right here if it doesn't
                     (try-catch-non-fatal (seq (user-fn ctx))
                                          e (do
                                              (timbre/error e)
                                              nil))))
   :default fiddle-dependent-requests})

(defn process-data [ctx]
  (mlet [request-fn (base/fn-from-mode (f-mode-config) (:hypercrud.browser/fiddle ctx) ctx)]
    (cats/return (request-fn ctx))))

(defn request-from-route [route ctx]
  (let [ctx (context/route ctx route)]
    (when-let [meta-fiddle-request (unwrap @(reactive/apply-inner-r (reactive/track base/meta-request-for-fiddle ctx)))]
      (assert (reactive/reactive? meta-fiddle-request))
      (concat [@meta-fiddle-request]
              (unwrap
                (mlet [fiddle @(reactive/apply-inner-r (reactive/track base/hydrate-fiddle meta-fiddle-request ctx))
                       fiddle-request @(reactive/apply-inner-r (reactive/track base/request-for-fiddle fiddle ctx))]
                  (assert (reactive/reactive? fiddle-request))
                  (cats/return
                    (concat
                      (some-> @fiddle-request vector)
                      (schema-util/schema-requests-for-link ctx)
                      (-> (base/process-results fiddle fiddle-request ctx)
                          (cats/bind process-data)
                          unwrap)))))))))

(defn request-from-link [link ctx]
  (unwrap (base/from-link link ctx (fn [route ctx]
                                     (either/right (request-from-route route ctx))))))
