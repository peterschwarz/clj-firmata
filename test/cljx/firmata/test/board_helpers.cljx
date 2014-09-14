(ns firmata.test.board-helpers
  (:require [firmata.core :refer [open-board]]))

(defn with-open-board 
  ([client on-board-ready] (with-open-board client [] on-board-ready))
  ([client options on-board-ready]
    #+clj (on-board-ready (apply (partial open-board client) options))
    #+cljs (apply (partial open-board client on-board-ready) options)))