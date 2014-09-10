(ns firmata.stream
  #+clj
  (:require [serial.core :as serial]
            [clojure.core.async :as a :refer [go <! timeout]])
  #+cljs
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async    :as a :refer  [<! timeout]]
            [firmata.messages :refer [SYSEX_START SYSEX_END PROTOCOL_VERSION
                                      is-digital? is-analog?]])
  #+clj
  (:import [java.net InetSocketAddress Socket]
           [java.io InputStream])
  #+cljs
  (:require-macros [cljs.core.async.macros :refer [go]]))


(defprotocol ByteReader
  (read! [this] "reads a byte, and removes it from the stream"))

#+clj
(extend-type InputStream

  ByteReader
  
  (read! [this] (.read this)))

#+cljs
(extend-type js/Buffer

  ByteReader

  (read! [this]
    (if (not (aget this "__current-index"))
      (aset this "__current-index" 0))

    (let [current-index (aget this "__current-index")
          value (if (< current-index (.-length this)) (.readUInt8 this current-index) -1)
          _ (aset this "__current-index" (inc current-index))]
      value)))

(defprotocol FirmataStream
  "A FirmataStream provides methods for creating connections, writing
  values to and listening for events on a Firmata-enabled device."

  (open! [this] "opens the stream")
  (close! [this] "closes the stream")

  (listen [this handler] "listens for data on this stream")
  (write [this data]))


#+clj 
(defrecord SerialStream [port-name baud-rate]
  FirmataStream

  (open! [this]
    (let [serial-port (serial/open port-name :baud-rate baud-rate)]
      (assoc this :serial-port serial-port)))

  (close! [this]
    (when-let [serial-port (:serial-port this)]
      (serial/close serial-port)
      (dissoc this :serial-port)))

  (write [this data]
    (when-let [serial-port (:serial-port this)]
      (serial/write serial-port data)))

  (listen [this handler]
    (when-let [serial-port (:serial-port this)]
      (serial/listen serial-port handler false))))

#+cljs
(def ^:private SerialPort (.-SerialPort (nodejs/require "serialport")))

#+cljs
(defn- concat-buffers [b1 b2]
  (.-concat js/Buffer) (object-array b1 b2))

#+cljs
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

#+cljs
(defrecord SerialStream [port-name baud-rate]
   FirmataStream

  (open! [this]
    (let [serial-port (SerialPort. 
                        (:port-name this) 
                        #js {:baudrate (:baud-rate this)
                             :parser (create-parser)})]
      (assoc this :serial-port serial-port)))

  (close! [this]
    (when-let [serial-port (:serial-port this)]
      (.close serial-port)
      (dissoc this :serial-port)))

  (listen [this handler]
    (when-let [serial-port (:serial-port this)]
      (.on serial-port "data" handler)))

  (write [this data]
    (when-let [serial-port (:serial-port this)]
      (.write serial-port data))))

(defn create-serial-stream [port-name baud-rate]
  (SerialStream. port-name baud-rate))


#+clj
(defrecord SocketClientStream [host port]
  FirmataStream

  (open! [this]
    (try
      (let [addr (InetSocketAddress. (:host this) (:port this))
            socket (Socket.)]
        (do
          (.setSoTimeout socket 0)
          (.connect socket addr)
          (assoc this :socket socket)))

      ; TODO: Is there a better way to deal with these?
      (catch java.net.UnknownHostException uhe
        (throw (RuntimeException. (str "Unknown host - " host) uhe)))

      (catch IllegalArgumentException iae
        (throw (RuntimeException. (str "Invalid port - " port) iae)))

      (catch java.net.SocketException se
        (throw (RuntimeException. (str "Unable to connect to " host ":" port) se)))))

  (close! [this]
    (when-let [socket (:socket this)]
      (.close (:socket this))
      (dissoc this :socket)))

  (write [this data]
    ; NOTE: This relies on the fact that we're using clj-serial,
    ; so we can use serial/to-bytes here
    (when-let [socket (:socket this)]
      (let [output-stream (.getOutputStream socket)]
        (.write output-stream (serial/to-bytes data))
        (.flush output-stream))))

  (listen [this handler]
    (when-let [socket (:socket this)]
      (go
        (while (.isConnected socket)
          (try
            (handler (.getInputStream socket))
            (catch java.net.SocketException se))
          ; slows the loop down to the the update rate of the device
          ; TODO: This should be configurable
          (<! (timeout 19)))))))

#+cljs
(defrecord SocketClientStream [host port]
  ; TODO: Implement in cljs
  )

(defn create-socket-client-stream [host port]
  (SocketClientStream. host port))
