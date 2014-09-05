(ns firmata.test.async-helpers
  (:require #+clj
            [clojure.core.async :as a :refer [go <!]]
            #+cljs
            [cljs.core.async    :as a :refer [<!]])
  #+cljs 
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn get-event
  [ch f]
  (go 
    (let [x (first (a/alts! [ch (a/timeout 200)]))]
      (f x))))
