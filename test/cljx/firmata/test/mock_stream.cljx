(ns firmata.test.mock-stream
  (:require [firmata.stream :refer [FirmataStream open! close! write listen]])
  #+clj 
  (:import [java.io ByteArrayInputStream]
           [java.nio ByteBuffer]))

(defprotocol Bytable
  (to-bytes [this] "Converts the type to bytes"))

(extend-protocol Bytable
  Number
  (to-bytes [this] (byte-array 1 (.byteValue this)))

  String
  (to-bytes [this] (.getBytes this "ASCII")))

(defn create-in-stream
  [& more]
  (let [buffer (ByteBuffer/allocate 256)]
    (reduce (fn [^ByteBuffer b ^bytes value] (.put b (to-bytes value))) buffer more)
    (ByteArrayInputStream. (.array buffer))))

(defrecord MockClientStream [state]
    FirmataStream
    (open! [this] 
      (swap! state  assoc :is-open? true)
      this)
    
    (close! [this] 
      (swap! state  assoc :is-open? false)
      this)
    
    (listen [_ handler] 
      (swap! state assoc :handler handler)
      (handler (create-in-stream 0xF9 9 9))
      (handler (create-in-stream 0xF0 0x79 9 9 "Test Firmware" 0xF7))
      nil)
    
    (write [_ data] (swap! state  assoc :last-write data)))

(defn create-mock-stream []
    (->MockClientStream (atom {})))

(defn- state [client k]
  (get (deref (:state client)) k))

(defn receive-bytes [client & more]
  ((state client :handler) (apply create-in-stream more)))

(defn last-write [client]
  #+clj 
  (Thread/sleep 100)
  (vec (flatten (state client :last-write))))

(defn is-open? [client]
  (state client :is-open?))