(ns firmata.test.board-helpers
  (:require [firmata.core :refer [open-board open-serial-board]]))

(defn with-open-board
  ([client on-board-ready] (with-open-board client [] on-board-ready))
  ([client options on-board-ready]
    #?(:clj (on-board-ready (apply (partial open-board client) options)))
    #?(:cljs (apply (partial open-board client on-board-ready) options))))

(defn with-serial-board
  [port-name on-board-ready]
  #?(:clj  (on-board-ready (open-serial-board port-name)))
  #?(:cljs (open-serial-board port-name on-board-ready)))
