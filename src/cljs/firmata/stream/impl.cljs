(ns firmata.stream.impl
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async    :as a :refer  [<! timeout]]
            [firmata.stream.spi :refer [FirmataStream ByteReader]]
            [firmata.messages :refer [SYSEX_START SYSEX_END PROTOCOL_VERSION
                                      is-digital? is-analog?]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

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
    ; TODO: This is an issue if the npm dependency is missing
    (.-SerialPort (nodejs/require "serialport"))
    (catch js/Error e 
      (.error js/console "Unable to required 'serialport': This may be due to a missing npm dependency.")
      nil)))

(defn- concat-buffers [b1 b2]
  (.-concat js/Buffer) (object-array b1 b2))

(defn- create-parser [] 
  (let [buffer (atom (js/Buffer 0))]
    (fn [emmitter data]
      (reset! buffer (concat-buffers @buffer data))
      (let [first-byte (aget @buffer 0)
            last-byte (aget @buffer (-> @buffer count dec))
            emit-and-clear (fn [] 
                            (.emit emmitter "data" @buffer)
                            (reset! buffer (js/Buffer 0)))]
        (cond 
          (and (= SYSEX_START first-byte) (= SYSEX_END last-byte)) 
            (emit-and-clear) 
          (or (= PROTOCOL_VERSION first-byte) (is-digital? first-byte) (is-analog? first-byte))
            (emit-and-clear))))))


(extend-type SerialPort
  FirmataStream

  (open! [this] this)

  (close! [this] (.close this) this)

  (listen [this handler]
    (.on this "data" handler)
    nil)

  (write [this data]
    (.write this data)
    nil))

(defn create-serial-stream [port-name baud-rate on-connected]
  (let [serial-port (SerialPort. 
                        port-name
                        #js {:baudrate baud-rate
                             :parser (create-parser)})]
    (.on serial-port "open" (fn []
        (on-connected serial-port)))))

(def Socket (.-Socket (nodejs/require "net")))

(extend-type Socket
  FirmataStream

  (open! [this] this)

  (close! [this] 
    (.end this) 
    this)

  (listen [this handler]
    (.on this "data" handler)
    nil)

  (write [this data]
    (.write this data)
    nil))

(defn create-socket-client-stream [host port on-connected]
  (let [socket (Socket.)]
    (.connect socket port host #(on-connected socket))))

