(ns firmata.test.async-helpers
  (:require #+clj
            [clojure.core.async :as a :refer [go <! <!! timeout]]
            #+cljs
            [cljs.core.async    :as a :refer [<!]])
  #+cljs 
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:private WAIT_TIME 100)

(defn get-event
  [ch f]
  (go 
    (let [x (first (a/alts! [ch (a/timeout WAIT_TIME)]))]
      (f x))))

(defn wait-for-it 
  ([f] (wait-for-it WAIT_TIME f))
  ([wait-time f]
    #+clj 
    (do 
      (<!! (timeout wait-time))
      (f))
    #+cljs
    (js/setTimeout #(f) wait-time)))