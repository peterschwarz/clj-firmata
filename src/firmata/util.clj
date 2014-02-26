(ns firmata.util
  (:require [clojure.core.async :as a]))


(defn to-hex-str
  "For debug output"
  [x] (str "0x" (.toUpperCase (Integer/toHexString x))))

