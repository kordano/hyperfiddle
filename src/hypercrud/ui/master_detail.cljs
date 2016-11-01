(ns hypercrud.ui.master-detail
  (:require [clojure.string :as string]
            [hypercrud.client.core :as hc]
            [hypercrud.client.tx :as tx-util]
            [hypercrud.form.option :as option]
            [hypercrud.ui.auto-control :refer [auto-control]]
            [hypercrud.ui.form :as form]))


(defn master-detail* [entity {:keys [field graph stage-tx!] :as widget-args} selected-cur & [filter-entities detail-renderer]]
  (let [ident (-> field :field/attribute :attribute/ident)
        options (option/gimme-useful-options field)
        detail-renderer (or detail-renderer form/form)
        temp-id! (partial hc/*temp-id!* (-> entity .-dbgraph .-dbval .-conn-id))
        li (fn [key label is-selected? on-click & retract]
             [:li {:key key :class (if is-selected? "selected")}
              retract
              ; todo should use navigate-cmp?
              [:a {:href "#" :on-click on-click} label]])]
    [:div.master-detail
     [:ul (doall (-> (->> (get entity ident)
                          (filter (or filter-entities (constantly true)))
                          (map (fn [child-entity]
                                 (let [dbid (:db/id child-entity)]
                                   (li (hash dbid)
                                       (get child-entity (option/label-prop options))
                                       (= dbid @selected-cur)
                                       #(reset! selected-cur dbid)
                                       [:button.retract-detail
                                        {:key "retract"
                                         :on-click #((stage-tx! (tx-util/edit-entity (:db/id entity) ident [dbid] []))
                                                     (reset! selected-cur nil))} "⌦"])))))
                     (concat (if (option/create-new? options entity)
                               [(li "create-new" "Create New" false
                                    #(let [dbid (temp-id!)]
                                      (stage-tx! (tx-util/edit-entity (:db/id entity) ident [] [dbid]))
                                      (reset! selected-cur dbid)))]
                               []))))]
     (if (nil? @selected-cur)
       [:span "Select the " (string/capitalize (name ident))]
       (let [selected-entity (hc/entity (.-dbgraph entity) @selected-cur)]
         ^{:key (hash @selected-cur)}
         [detail-renderer graph selected-entity
          (option/get-form options entity)
          ((:expanded-cur widget-args) [(:db/id entity)])
          (:stage-tx! widget-args)
          (:navigate-cmp widget-args)]))]))
