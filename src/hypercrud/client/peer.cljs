(ns hypercrud.client.peer
  (:require [clojure.set :as set]
            [hypercrud.client.core :as hc]
            [hypercrud.client.http :as http]
            [hypercrud.client.response :as response]
            [hypercrud.util.core :as util]
            [promesa.core :as p]))


(defn hydrate! [entry-uri requests stage-val]
  (-> (http/hydrate! entry-uri requests stage-val)
      (p/then (fn [{:keys [t pulled-trees-map]}]
                (response/->Response (into #{} requests) pulled-trees-map stage-val)))))

(defn transact! [entry-uri stage-val]
  (let [htx-groups (->> stage-val
                        (util/map-values (fn [branch-tx]
                                           (->> (get branch-tx nil)
                                                (filter (fn [[op e a v]]
                                                          (not (and (or (= :db/add op) (= :db/retract op))
                                                                    (nil? v)))))))))]
    (http/transact! entry-uri htx-groups)))

(defn hydrated? [last-response requests]
  ; compare our pre-loaded state with the peer dependencies
  (set/subset? (set requests) (some-> last-response .-requests)))

(deftype Peer [entry-uri stage last-response]
  hc/Peer
  (hydrate! [this request]
    (hydrate! entry-uri request @stage))

  ; for clone link - is this bad? yeah its bad since it can never be batched.
  (hydrate-one! [this request]
    (-> (hydrate! entry-uri #{request} @stage)
        (p/then (fn [response] (hc/hydrate response request)))))

  (hydrated? [this requests]
    (hydrated? @last-response requests))

  (transact! [this]
    (transact! entry-uri @stage)))
