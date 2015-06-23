(ns firmata.stream.impl
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async    :as a :refer  [<! timeout]]
            [firmata.stream.spi :refer [FirmataStream ByteReader]]
            [firmata.messages :refer [SYSEX_START SYSEX_END PROTOCOL_VERSION
                                      is-digital? is-analog?]])
  (:require-macros [cljs.core.async.macros :refer [go]]))


(defprotocol Bytable
  (to-bytes [this] "Converts the type to bytes"))

(extend-protocol Bytable

  number 
  (to-bytes [this] 
    (let [b (js/Buffer. 1)]
      (.writeUInt8 b this 0)
      b))

  string
  (to-bytes [this] 
    (js/Buffer. this)))

(defn make-buffer [value]
  (if (coll? value) 
    ((.-concat js/Buffer) (to-array (map #(to-bytes %) value)))
    (to-bytes value)))

(extend-type js/Buffer

  ByteReader

  (read! [this]
    (if (not (aget this "__current-index"))
      (aset this "__current-index" 0))

    (let [current-index (aget this "__current-index")
          value (if (< current-index (.-length this)) (.readUInt8 this current-index) -1)
          _ (aset this "__current-index" (inc current-index))]
      value)))

(defrecord ErrorReader [error]
  ByteReader
  (read! [_]
    (throw error)))

(def ^:private SerialPort 
  (try
    (.-SerialPort (nodejs/require "serialport"))
    (catch js/Error e 
      (.error js/console "Unable to require 'serialport': This may be due to a missing npm dependency.")
      nil)))

(defn- afirst [a]
  (aget a 0))

(defn- alast [a]
  (aget a (-> a alength dec)))

(defn- valid-cmd? [b]
  (or (= PROTOCOL_VERSION b) (is-digital? b) (is-analog? b)))

(defn create-preparser [on-complete-data error-atom] 
  (let [buffer (make-array 0)
        buf-reset! #(set! (.-length buffer) 0)
        emit-and-clear (fn [] 
                         (on-complete-data (js/Buffer buffer))
                         (buf-reset!))]
    (fn [data]
      (when @error-atom
        (on-complete-data (ErrorReader. @error-atom)))
      (doseq [b (vec (prim-seq data))]
        (when (not (= 0 (alength buffer) b))
          (.push buffer b)
          (let [first-byte (afirst buffer)
                last-byte (alast buffer)]
            (cond 
              (and (= SYSEX_START first-byte) (= SYSEX_END last-byte)) 
              (emit-and-clear)
              (and (valid-cmd? first-byte) (= 3 (alength buffer))) ;; non-sysex command
              (emit-and-clear)
              (not (or (= SYSEX_START first-byte) (valid-cmd? first-byte))) ;; out of sync
              (buf-reset!))))))))

(defn- on-data [listenable handler error-atom]
  (let [preparser (create-preparser handler error-atom)]
    (.on listenable "data" preparser))
  nil)

(defn- write-data [writer data]
  (.write writer (make-buffer data))
  nil)

(defn- as-streamable [source close-fn]
  (let [last-error (atom nil)]
    (.on source "error" #(reset! last-error %))

    (reify 
      FirmataStream
      (open! [this] this)

      (close! [this] (close-fn source) this)

      (listen [_ handler]
        (on-data source handler last-error))

      (write [_ data]
        (if-not @last-error
          (write-data source data)
          (throw @last-error))))))

(defn create-serial-stream
  "Creates a serial stream on the given port-name, with the given baud-rate, and
   returns resulting stream via the on-connected callback function.
  
  The callback function should have the signatures of `(fn [stream])`"
  [port-name baud-rate on-connected]
  (let [serial-port (SerialPort. port-name #js {:baudrate baud-rate})]
    (.on serial-port "open" #(on-connected (as-streamable serial-port (memfn close))))))

(def ^:private net (nodejs/require "net"))
(def ^:private Socket (.-Socket net))

(defn create-socket-client-stream
  "Creates a socket stream on the given host and port, and
   returns resulting stream via the on-connected callback function.
  
  The callback function should have the signatures of `(fn [stream])`"
  [host port on-connected]
  (let [socket (Socket.)]
    (.connect socket port host #(on-connected (as-streamable socket (memfn end))))))

(defn create-socket-server-stream [host port on-connected]
  (let [server (.createServer net #(on-connected (as-streamable % (memfn end))))]
    (.listen server port host)
    ; TODO: This should log; here to handle errors
    (.on server "error" #(println (js->clj %)))
    server))
