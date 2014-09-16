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

(defn create-preparser [on-complete-data] 
  (let [buffer (make-array 0)
        buf-reset! #(set! (.-length buffer) 0)
        emit-and-clear (fn [] 
                        (on-complete-data (js/Buffer buffer))
                        (buf-reset!))]
    (fn [data]
      ; Todo; this seems a bit expensive, need to see if there may be a bug fix
      (doseq [b (vec (aclone data))]
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

(defn- on-data [listenable handler]
  (let [preparser (create-preparser handler)]
    (.on listenable "data" preparser))
  nil)

(defn- write-data [writer data]
  (.write writer (make-buffer data))
  nil)

(extend-type SerialPort
  FirmataStream

  (open! [this] this)

  (close! [this] (.close this) this)

  (listen [this handler]
    (on-data this handler))

  (write [this data]
    (write-data this data)))

(defn create-serial-stream [port-name baud-rate on-connected]
  (let [serial-port (SerialPort. port-name #js {:baudrate baud-rate})]
    (.on serial-port "open" #(on-connected serial-port))))

(def Socket (.-Socket (nodejs/require "net")))

(extend-type Socket
  FirmataStream

  (open! [this] this)

  (close! [this] 
    (.end this) 
    this)

  (listen [this handler]
    (on-data this handler))

  (write [this data]
    (write-data this data)))

(defn create-socket-client-stream [host port on-connected]
  (let [socket (Socket.)]
    (.connect socket port host #(on-connected socket))))
