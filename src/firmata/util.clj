(ns firmata.util
  (:require [clojure.core.async :as a]))


(defn to-hex-str
  "For debug output"
  [x] (str "0x" (.toUpperCase (Integer/toHexString x))))

(defn create-event-receiver
  [board event-handler]
  (a/go (loop [event (<! (:channel board))]
          (event-handler event)
          (recur (<! (:channel board))))))

(defn stop-receiver
  [receiver]
  (a/close! receiver))

