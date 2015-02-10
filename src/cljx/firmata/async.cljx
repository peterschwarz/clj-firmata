(ns firmata.async
  (:require [firmata.core :refer [event-publisher]]
            #+clj
             [clojure.core.async :as a :refer [go go-loop <! >!]]
            #+clj
             [clojure.core.async.impl.protocols :as p :refer [Channel ReadPort take! close! closed?]]
            #+cljs
             [cljs.core.async    :as a :refer [<! >!]]
            #+cljs
             [cljs.core.async.impl.protocols :as p :refer [Channel ReadPort take! close! closed?]])
  #+cljs
   (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def ^:private MAX_VAL 
  #+clj Integer/MAX_VALUE
  #+cljs 9007199254740992)

(defn- subscription-chan
  [board target buf-or-n]
  (let [filtered-ch (a/chan buf-or-n)]
    (a/sub (event-publisher board) target filtered-ch)
    filtered-ch))

(defn- wrap-chan [board topic filtered-ch out-ch]
  (reify 
    Channel
    (close! [_]
      (a/unsub (event-publisher board) topic filtered-ch)
      (a/close! filtered-ch))
    (closed? [_]
      (closed? filtered-ch))

    ReadPort
    (take! [_ fn1-handler]
      (take! out-ch fn1-handler))))

#+clj
 (defn is-event?
   "predicate for a firmata event"
   [evt]
   (= (type evt) clojure.lang.PersistentArrayMap))

#+cljs
 (defn is-event?
   "predicate for a firmata event"
   [evt]
   (= (type evt) cljs.core/PersistentArrayMap))

(defn digital-event-chan 
  "Creates an async channel for digital events on a given pin.

  Returns a map with the channel at :chan and a function to close the channel at :close-fn"
  ([board digital-pin] (digital-event-chan board digital-pin nil))
  ([board digital-pin buf-or-n]
   (let [topic [:digital-msg digital-pin]
         filtered-ch (subscription-chan board topic buf-or-n)]
     (wrap-chan board topic filtered-ch filtered-ch))))

(defn analog-event-chan
  "Creates an async channel for digital events on a given pin.

  Returns a map with the channel at :chan and a function to close the channel at :close-fn"
  [board analog-pin  & {:keys [delta buf-or-n]
                        :or {delta 5 buf-or-n nil}}]
  (let [topic [:analog-msg analog-pin]
        filtered-ch (subscription-chan board topic buf-or-n)
        out (a/chan buf-or-n)]
    (go
      (loop [prev nil
             event (<! filtered-ch)]
        (when (and 
               event 
               (< delta (Math/abs (- (:value prev MAX_VAL) (:value event)))))
          (>! out event)
          (recur event
                 (<! filtered-ch)))))
    (wrap-chan board topic filtered-ch out)))
