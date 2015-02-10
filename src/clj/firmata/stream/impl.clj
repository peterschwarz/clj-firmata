(ns firmata.stream.impl
  (:require [firmata.stream.spi :refer [FirmataStream ByteReader]]
            [serial.core :as serial]
            [clojure.core.async :as a :refer [go go-loop <! timeout]])
  (:import [java.net InetSocketAddress Socket ServerSocket]
           [java.io InputStream]))

(extend-type InputStream

  ByteReader
  
  (read! [this] (.read this)))

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

(defn create-serial-stream [port-name baud-rate]
  (SerialStream. port-name baud-rate))

(defn- close-socket [socket-client]
  (when-let [socket (:socket socket-client)]
    (.close (:socket socket-client))
    (dissoc socket-client :socket)))

(defn- write-socket [socket-client data]
  ; NOTE: This relies on the fact that we're using clj-serial,
  ; so we can use serial/to-bytes here
  (when-let [socket (:socket socket-client)]
    (let [output-stream (.getOutputStream socket)]
      (.write output-stream (serial/to-bytes data))
      (.flush output-stream))))

(defn- listen-socket [socket-client handler]
  (when-let [socket (:socket socket-client)]
    (go
      (while (.isConnected socket)
        (try
          (handler (.getInputStream socket))
          (catch java.net.SocketException se))
        ; slows the loop down to the the update rate of the device
        ; TODO: This should be configurable; possibly also in microseconds
        (<! (timeout 19))))))

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
    (close-socket this))

  (write [this data]
    (write-socket this data))

  (listen [this handler]
    (listen-socket this handler)))

(defrecord ServerSocketStream [socket]
  FirmataStream
  
  (open! [this] this)

  (close! [this]
    (close-socket this))

  (write [this data]
    (write-socket this data))

  (listen [this handler]
    (listen-socket this handler)))

(defn create-socket-client-stream [host port]
  (SocketClientStream. host port))

(defn- safe-accept [server]
  (try 
    (.accept server)
    (catch java.io.IOException e
      nil)))

(defn create-socket-server-stream [host port on-connected]
  (let [addr (InetSocketAddress. host port)
        server (ServerSocket.)]
    (.bind server addr)
    (go-loop [socket (safe-accept server)]
      (when socket
        (go 
          (on-connected (ServerSocketStream. socket)))
        (recur (safe-accept server))))
    server))
