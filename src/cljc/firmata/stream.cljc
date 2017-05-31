(ns firmata.stream
  (:require [firmata.stream.impl :as impl]))

(defn create-serial-stream [port-name baud-rate #?(:cljs on-connected)]
  (impl/create-serial-stream port-name baud-rate #?(:cljs on-connected)))

(defn create-socket-client-stream [host port #?(:cljs on-connected)]
  (impl/create-socket-client-stream host port #?(:cljs on-connected)))

(defn create-socket-server-stream [host port on-connected]
  (impl/create-socket-server-stream host port on-connected))
