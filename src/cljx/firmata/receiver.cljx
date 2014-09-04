(ns firmata.receiver
  (:require [firmata.core :refer [event-channel event-publisher release-event-channel]]
            #+clj
            [clojure.core.async :as a :refer [go <!]]
            #+cljs
            [cljs.core.async    :as a :refer [<!]])
  #+cljs
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defprotocol EventHandler
  (stop-receiver! [handler] "Stops receiving events on a given handler"))

(defn- on-channel-event-with-prev
  [channel event-handler]
  (go
   (loop [prev nil
          event (<! channel)]
     (when event
       (event-handler event prev)
       (recur event
              (<! channel))))))

(defn- on-channel-event
  [channel event-handler]
  (on-channel-event-with-prev channel (fn [event prev] (event-handler event))))

(defn on-event
  "Add a general event receiver. `event-handler` takes should take one argument: event."
  [board event-handler]
  (let [ch (event-channel board)]
    (on-channel-event ch event-handler)
    (reify EventHandler
      (stop-receiver!
       [this]
       (release-event-channel board ch)))))

(defn- subscription-chan
  [board target]
  (let [filtered-ch (a/chan)]
    (a/sub (event-publisher board) target filtered-ch)
    filtered-ch))

(defn- unsub-handler
  [board channel topic]
  (reify EventHandler
    (stop-receiver!
     [this]
     (a/unsub (event-publisher board) topic channel))))

(defn on-digital-event
  "Creates a receiver for digital events on a given pin. `event-handler` takes should take one argument: event."
  [board digital-pin event-handler]
  (let [topic [:digital-msg digital-pin]
        filtered-ch (subscription-chan board topic)]
    (on-channel-event filtered-ch event-handler)
    (unsub-handler board filtered-ch topic)))

(defn on-analog-event
  "Create a receiver for analog events on a given pin. `event-handler` takes should take one argument: event."
  ([board analog-pin event-handler] (on-analog-event board analog-pin event-handler 5))
  ([board analog-pin event-handler delta]
  (let [topic [:analog-msg analog-pin]
        filtered-ch (subscription-chan board topic)]
    (on-channel-event-with-prev
     filtered-ch
     (fn [event prev]
       (when (< delta (Math/abs (- (:value prev Integer/MAX_VALUE) (:value event))))
         (event-handler event))))
    (unsub-handler board filtered-ch topic))))

