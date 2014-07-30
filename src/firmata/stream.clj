(ns firmata.stream
  (:require [serial.core :as serial]
            [clojure.core.async :as a :refer [go <!! timeout]])
  (:import [java.net InetSocketAddress Socket]))

(defprotocol FirmataStream

  (open! [this] "opens the stream")
  (close! [this] "closes the stream")

  (listen [this handler] "listens for data on this stream")
  (write [this data]))


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


(defrecord SocketStream [host port]
  FirmataStream

  (open! [this] 
    (let [addr (InetSocketAddress. (:host this) (:port this))
          socket (Socket.)]
      (.setSoTimeout socket 0)
      (.connect socket addr)
      (assoc this :socket socket)))

  (close! [this]
    (.close (:socket this))
    (dissoc this :socket))

  (write [this data]
    ; TODO: This relies on the fact that we're using clj-serial, 
    ; so we can use serial/to-bytes here
    (let [socket (:socket this)
          output-stream (.getOutputStream socket)]
      (.write output-stream (serial/to-bytes data))
      (.flush output-stream)))

  (listen [this handler] 
    (go
      (let [socket (:socket this)]
        (while (.isConnected socket)
          (try 
            (handler (.getInputStream socket))
            (catch java.net.SocketException se)))))))
