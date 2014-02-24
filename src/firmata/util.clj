(ns firmata.util
  (:require [clojure.core.async :as a]))


(defn to-hex-str
  "For debug output"
  [x] (str "0x" (.toUpperCase (Integer/toHexString x))))

(defn create-event-receiver
  "Add a general event handler"
  [board event-handler]
  (let [control-chan (a/chan 1)]
    (a/go (loop [control :running
                 event (<! (:channel board))]
            (when event
              (event-handler event)
              (>! control-chan :running)
              (recur (<! control-chan)
                     (<! (:channel board))))))
    control-chan))

(defn on-analog-event
  ([board analog-pin event-handler] (on-analog-event board analog-pin event-handler 5))
  ([board analog-pin event-handler delta]
  (let [control-chan (a/chan 1)
        filtered-ch (a/pipe (:channel board) (a/filter> #(= (select-keys % [:type :pin]) {:type :analog-msg :pin analog-pin}) (a/chan)) true)]
    (a/go (loop [control :running
                 prev nil
                 event (<! filtered-ch)]
            (when (and control event)
              (when (< delta (Math/abs (- (:value prev Integer/MAX_VALUE) (:value event))))
                (event-handler event))
                (>! control-chan :running)
              (recur (<! control-chan)
                     event
                     (<! filtered-ch)))))
    control-chan)))

(defn stop-receiver
  [receiver]
  (a/close! receiver))

