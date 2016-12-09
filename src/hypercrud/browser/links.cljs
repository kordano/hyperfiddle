(ns hypercrud.browser.links
  (:require [hypercrud.browser.base-64-url-safe :as base64]
            [hypercrud.client.core :as hc]
            [hypercrud.client.internal :as internal]
            [hypercrud.compile.eval :refer [eval]]
            [hypercrud.form.q-util :as q-util]))


(defn field-link [field-dbid dbid]
  ;; field-dbid is assumed to be the editor-graph connection
  {:href (str (base64/encode (pr-str field-dbid)) "/field/" (base64/encode (pr-str dbid)))})


(defn build-query-params [link param-ctx]
  (let [query-params (q-util/build-params-map link param-ctx)]
    {:link-dbid (.-dbid link)
     :query-params query-params
     ;; Create a result of shape [?e ?f] with new entities colored
     :create-new-find-elements (->> (:link/find-element link)
                                    (mapv (juxt :find-element/name #(hc/*temp-id!* (-> % :find-element/connection :db/id :id))))
                                    (into {}))}))


(defn query-link [stage-tx! link param-ctx]
  (let [tx-fn (if-let [tx-fn (:link/tx-fn link)]
                (let [{value :value error :error} (eval tx-fn)]
                  ;; non-fatal error, report it here so user can fix it
                  (if error (js/alert (str "cljs eval error: " error)))
                  value))]
    ;; link-dbid is assumed to be the editor-graph connection

    ;; add-result #(tx/edit-entity (:db/id entity) ident [] [(first %)])
    (if tx-fn
      {:on-click #(stage-tx! (tx-fn param-ctx))}
      {:href (base64/encode (pr-str (build-query-params link param-ctx)))})))
