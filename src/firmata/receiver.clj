(ns firmata.receiver
  (:require [clojure.core.async :as a]))

(defrecord EventReceiver [handler control-channel])

(defn- on-channel-event
  [channel event-handler]
  (let [control-chan (a/chan 1)]
    (a/go
     (>! control-chan :running)
     (loop [control (<! control-chan)
            event (<! channel)]
       (when (and control event)
         (event-handler event)
         (>! control-chan :running)
         (recur (<! control-chan)
                (<! channel)))))
    (EventReceiver. event-handler control-chan)))

(defn on-event
  "Add a general event receiver. `event-handler` takes should take one argument: event."
  [board event-handler]
  (on-channel-event (:channel board) event-handler))

(defn on-digital-event
  "Creates a receiver for digital events on a given pin. `event-handler` takes should take one argument: event."
  [board digital-pin event-handler]
  (let [filtered-ch (a/pipe (:channel board)
                            (a/filter> #(= (select-keys % [:type :pin])
                                           {:type :digital-msg, :pin digital-pin})
                                       (a/chan)) true)]
    (on-channel-event filtered-ch event-handler)))

(defn on-analog-event
  "Create a receiver for analog events on a given pin. `event-handler` takes should take one argument: event."
  ([board analog-pin event-handler] (on-analog-event board analog-pin event-handler 5))
  ([board analog-pin event-handler delta]
  (let [control-chan (a/chan 1)
        pipe (a/pipe (:channel board) (a/chan) true)
        filtered-ch (a/filter< #(= (select-keys % [:type :pin]) {:type :analog-msg :pin analog-pin}) pipe)]
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
    (EventReceiver. event-handler control-chan))))

(defn stop-receiver!
  "Stops receiving events on a given receiver"
  [receiver]
  (a/close! (:control-channel receiver)))

