(ns hypercrud.browser.browser-ui
  (:require [cats.core :as cats :refer [mlet]]
            [cats.monad.either :as either]
            [contrib.css :refer [css-slugify classes]]
            [contrib.data :refer [cond-let map-values unwrap kwargs]]
            [contrib.eval :as eval]
            [contrib.reactive :as r]
            [contrib.string :refer [memoized-safe-read-edn-string pprint-str]]
            [contrib.try :refer [try-either]]
            [cuerdas.core :as string]
            [hypercrud.browser.base :as base]
            [hypercrud.browser.context :as context]
            [hypercrud.browser.link :as link]
            [hypercrud.browser.routing :as routing]
            [hypercrud.types.ThinEntity :refer [->ThinEntity]]
            [hypercrud.ui.error :as ui-error]
    ; [hypercrud.ui.form :as form]
            [contrib.ui.native-event-listener :refer [native-on-click-listener]]
            [hypercrud.ui.safe-render :refer [user-portal]]
            [hypercrud.ui.stale :as stale]
            [hyperfiddle.foundation :as foundation]
            [hyperfiddle.foundation.actions :as foundation-actions]
            [hyperfiddle.runtime :as runtime]))


(declare ui-from-link)

(defn fiddle-css-renderer [s]
  [:style {:dangerouslySetInnerHTML {:__html @s}}])

(defn auto-ui-css-class [ctx]
  (classes (let [ident @(r/cursor (:hypercrud.browser/fiddle ctx) [:fiddle/ident])]
             [(css-slugify (some-> ident namespace))
              (css-slugify ident)
              "auto-result"])))

(letfn [(browse [rel #_dependent? path ctx ?f & ?args]
          (let [props (kwargs ?args)
                {:keys [:link/dependent? :link/path] :as link} @(r/track link/rel->link rel path ctx)
                ctx (-> (context/relation-path ctx (into [dependent?] (unwrap (memoized-safe-read-edn-string (str "[" path "]")))))
                        (as-> ctx (if ?f (assoc ctx :user-renderer ?f #_(if ?f #(apply ?f %1 %2 %3 %4 ?args))) ctx)))]
            (into [ui-from-link link ctx (:class props)] (apply concat (dissoc props :class :children nil)))))
        (anchor [rel #_dependent? path ctx label & args]
          (let [kwargs (kwargs args)
                {:keys [:link/dependent? :link/path] :as link} @(r/track link/rel->link rel path ctx)
                ctx (context/relation-path ctx (into [dependent?] (unwrap (memoized-safe-read-edn-string (str "[" path "]")))))
                props (link/build-link-props link ctx)]
            [(:navigate-cmp ctx) props label (:class kwargs)]))
        (cell [[d i a] ctx ?f & args]                       ; form only
          (let [props (kwargs args)]
            (into [(r/partial hypercrud.ui.form/Cell ?f)    ; Intentional explicit nil
                   (context/relation-path ctx [d i a])]
                  args)))
        (value [path ctx & [?f ?props]]
          ; The most important thing to do here is override the renderer.
          ; Then, optionally pass props. kwargs can partial props but we don't need that here? Why partial a :class?
          (let [ctx (context/relation-path ctx path)
                field (:hypercrud.browser/field ctx)
                #_#_control-props (merge (hypercrud.ui.auto-control/control-props ctx) ?props)]
            [(or ?f (r/partial hypercrud.ui.auto-control/auto-control {} nil)) ctx]))
        (browse' [rel #_dependent? path ctx]
          (->> (base/data-from-link @(r/track link/rel->link rel path ctx) ctx)
               (cats/fmap :hypercrud.browser/result)
               (cats/fmap deref)))
        (anchor* [rel #_dependent? path ctx]
          (link/build-link-props @(r/track link/rel->link rel path ctx) ctx))]
  ; convenience functions, should be declared fns in this or another ns and accessed out of band of ctx
  (defn ui-bindings [ctx]
    (assoc ctx
      :anchor anchor
      :browse browse
      :cell cell
      :value value
      :anchor* anchor*
      :browse' browse')))

(defn page-on-click [rt branch branch-aux route event]
  (when (and route (.-altKey event))
    (runtime/dispatch! rt (fn [dispatch! get-state]
                            (when (foundation/navigable? route (get-state))
                              (foundation-actions/set-route rt route branch false false dispatch! get-state))))
    (.stopPropagation event)))

; defer eval until render cycle inside userportal
(let [safe-eval-string #(try-either (eval/eval-string %))   ; don't actually need to safely eval, just want to memoize exceptions
      memoized-eval-string (memoize safe-eval-string)]
  (defn eval-renderer-comp [renderer-str & args]
    (either/branch
      (memoized-eval-string renderer-str)
      (fn [e] (throw e))
      (fn [f] (into [f] args)))))

(defn build-renderer-str [user-str] (str "(fn [ctx & [class]]\n" user-str ")"))

(defn src-mode [ctx]
  (either/branch
    (mlet [request @(r/apply-inner-r (r/track base/meta-request-for-fiddle ctx))
           :let [fiddle (r/atom {:fiddle/type :entity})     ; turns out we dont need fiddle for much if we already know the request
                 ctx (-> (context/source-mode ctx)
                         (context/clean)
                         (context/route [nil [(->ThinEntity "$" [:fiddle/ident (first (:route ctx))])]]))]]
      (base/process-results fiddle request ctx))
    (fn [e] (throw e))                                      ; just throw, this is inside a user-portal
    (fn [ctx]
      (let [f (or (:user-renderer ctx) hyperfiddle.ide.fiddles.fiddle-src/fiddle-src-renderer)]
        [f ctx (auto-ui-css-class ctx) :embed-mode true]))))

(defn ui-comp [ctx]
  [user-portal (ui-error/error-comp ctx)
   (if (hyperfiddle.ide.fiddles.topnav/src-mode? (get (:route ctx) 3))
     [src-mode ctx]
     (case @(:hypercrud.ui/display-mode ctx)
       :user (cond-let
               [user-renderer (:user-renderer ctx)]
               [user-renderer ctx (auto-ui-css-class ctx)]

               [renderer-str (let [renderer-str @(r/cursor (:hypercrud.browser/fiddle ctx) [:fiddle/renderer])]
                               (when (and (string? renderer-str) (not (string/blank? renderer-str)))
                                 renderer-str))]
               [eval-renderer-comp (build-renderer-str renderer-str) ctx (auto-ui-css-class ctx)]

               [_ :else]
               ; todo ui.result should be injected
               [hypercrud.ui.result/fiddle ctx (auto-ui-css-class ctx)])

       ; todo ui.result should be injected
       :xray [hypercrud.ui.result/fiddle-xray ctx (auto-ui-css-class ctx)]))])

(defn ui-from-route [route ctx & [class]]
  (let [click-fn (or (:hypercrud.browser/page-on-click ctx) (constantly nil)) ; parent ctx receives click event, not child frame
        either-v (->> (or (some-> @(runtime/state (:peer ctx) [::runtime/partitions (:branch ctx) :error]) either/left)
                          (base/data-from-route route ctx))
                      (cats/fmap ui-bindings))
        error-comp (ui-error/error-comp ctx)]
    [stale/loading (stale/can-be-loading? ctx) either-v
     (fn [e]
       (let [on-click (r/partial click-fn route)]
         [native-on-click-listener {:on-click on-click}
          [:div {:class (classes "ui" class "hyperfiddle-error")}
           [error-comp e]]]))
     (fn [ctx]
       (let [on-click (r/partial click-fn (:route ctx))]
         [native-on-click-listener {:on-click on-click}
          [:div {:class (classes "ui" class)}
           [ui-comp ctx]
           [fiddle-css-renderer (r/cursor (:hypercrud.browser/fiddle ctx) [:fiddle/css])]]]))
     (fn [ctx]
       (let [on-click (r/partial click-fn (:route ctx))]
         ; use the stale ctx's route, otherwise alt clicking while loading could take you to the new route, which is jarring
         [native-on-click-listener {:on-click on-click}
          [:div {:class (classes "ui" class "hyperfiddle-loading")}
           [ui-comp ctx]
           [fiddle-css-renderer (r/cursor (:hypercrud.browser/fiddle ctx) [:fiddle/css])]]]))]))

(defn ui-from-link [link ctx ?class & args]
  (let [props (kwargs args)
        error-comp (ui-error/error-comp ctx)
        hidden' (->> (try-either (link/build-link-props link ctx)) ; todo we want the actual error from the link props
                     (cats/fmap :hidden))]
    [stale/loading (stale/can-be-loading? ctx) hidden'
     (fn [e] [error-comp e])
     (fn [link-props]
       (if (:hidden link-props)
         [:noscript]
         [stale/loading (stale/can-be-loading? ctx) (routing/build-route' link ctx (:frag props))
          (fn [e] [error-comp e])
          (fn [route] [ui-from-route route ctx (classes ?class (css-slugify (:link/rel link)))])]))]))
