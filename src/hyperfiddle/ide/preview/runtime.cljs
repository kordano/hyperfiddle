(ns hyperfiddle.ide.preview.runtime
  (:require
    [hyperfiddle.api :as hf]
    [hyperfiddle.ide.routing :as ide-routing]
    [hyperfiddle.runtime :as runtime]
    [hyperfiddle.runtime-impl :as runtime-impl]))


(def preview-pid "user")

(deftype Runtime [ide-rt preview-pid domain io state-atom]
  hf/State
  (state [rt] state-atom)

  hf/HF-Runtime
  (domain [rt] domain)
  (io [rt] io)
  (request [rt pid request] (runtime-impl/hydrate-impl rt pid request))
  (set-route [rt pid route] (hf/set-route rt pid route false))
  (set-route [rt pid preview-route force-hydrate]
    (if (= preview-pid pid)
      ; just blast the ide's route and let things trickle down
      (let [ide-route (ide-routing/preview-route->ide-route preview-route)]
        (runtime-impl/set-route ide-rt (runtime/parent-pid rt pid) ide-route force-hydrate))
      (runtime-impl/set-route rt pid preview-route force-hydrate)))

  IEquiv
  (-equiv [o other]
    (and (instance? Runtime other)
         (= (.-ide-rt o) (.-ide-rt other))
         (= (.-preview-pid o) (.-preview-pid other))
         (= (.-domain o) (.-domain other))
         (= (.-io o) (.-io other))
         (= (.-state-atom o) (.-state-atom other))))

  IHash
  (-hash [this] (hash [ide-rt preview-pid domain io state-atom])))
