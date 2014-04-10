(ns firmata.receiver
  (:require [firmata.core :refer :all]
            [clojure.core.async :as a]))

(defrecord EventReceiver [handler source-channel])

(defn- on-channel-event-with-prev
  [channel event-handler]
  (a/go
   (loop [prev nil
          event (<! channel)]
     (when event
       (event-handler event prev)
       (recur event
              (<! channel)))))
  (EventReceiver. event-handler channel))

(defn- on-channel-event
  [channel event-handler]
  (on-channel-event-with-prev channel (fn [event prev] (event-handler event))))

(defn on-event
  "Add a general event receiver. `event-handler` takes should take one argument: event."
  [board event-handler]
  (on-channel-event (event-channel board) event-handler))

(defn- subscription-chan
  [board target]
  (let [filtered-ch (a/chan)]
    (a/sub (event-publisher board) target filtered-ch)
    filtered-ch))

(defn on-digital-event
  "Creates a receiver for digital events on a given pin. `event-handler` takes should take one argument: event."
  [board digital-pin event-handler]
  (let [filtered-ch (subscription-chan board [:digital-msg digital-pin])]
    (on-channel-event filtered-ch event-handler)))

(defn on-analog-event
  "Create a receiver for analog events on a given pin. `event-handler` takes should take one argument: event."
  ([board analog-pin event-handler] (on-analog-event board analog-pin event-handler 5))
  ([board analog-pin event-handler delta]
  (let [filtered-ch (subscription-chan board [:analog-msg analog-pin])]
    (on-channel-event-with-prev
     filtered-ch
     (fn [event prev]
       (when (< delta (Math/abs (- (:value prev Integer/MAX_VALUE) (:value event))))
         (event-handler event)))))))

(defn stop-receiver!
  "Stops receiving events on a given receiver"
  [receiver]
  (a/close! (:source-channel receiver)))
