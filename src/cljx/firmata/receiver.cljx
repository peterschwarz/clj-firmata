(ns firmata.receiver
  "Deprecated: use firmata.core/event-channel and firmata.core/release-event-channel directly
  for general events.  For analgo and digital events, use firmata.async's channel functions."
  (:require [firmata.core :refer [event-channel release-event-channel]]
            [firmata.async :refer [digital-event-chan analog-event-chan]]
            #+clj
            [clojure.core.async :as a :refer [go go-loop <!]]
            #+cljs
            [cljs.core.async    :as a :refer [<!]])
  #+cljs
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defprotocol EventHandler
  "Deprecated" 
  (stop-receiver! [handler] "Stops receiving events on a given handler"))

(defn- evt-loop [ch event-handler]
  (go-loop [evt (<! ch)]
    (when evt
      (event-handler evt)
      (recur (<! ch)))))

(defn on-event
  "Deprecated

  Add a general event receiver. `event-handler` takes should take one argument: event."
  [board event-handler]
  (let [ch (event-channel board)]
    (evt-loop ch event-handler)
    (reify EventHandler
      (stop-receiver!
       [this]
       (release-event-channel board ch)))))

(defn- unsub-handler
  [channel]
  (reify EventHandler
    (stop-receiver!
     [this]
     (a/close! channel))))

(defn- on-subscription-event
  [ch event-handler]
  (evt-loop ch event-handler)
  (unsub-handler ch))

(defn on-digital-event
  "Deprecated

  Creates a receiver for digital events on a given pin. 
  `event-handler` takes should take one argument: event."
  [board digital-pin event-handler]
  (on-subscription-event (digital-event-chan board digital-pin) event-handler))

(defn on-analog-event
  "Deprecated

  Create a receiver for analog events on a given pin. 
  `event-handler` takes should take one argument: event."
  ([board analog-pin event-handler] (on-analog-event board analog-pin event-handler 5))
  ([board analog-pin event-handler delta]
  (on-subscription-event (analog-event-chan board analog-pin :delta delta) event-handler)))
