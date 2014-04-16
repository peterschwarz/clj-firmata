(ns firmata.util
  (:require [clojure.core.async :as a]))

(defn arduino-map
  "Clojure implemation of the Arduino map function.
  http://arduino.cc/en/reference/map"
  [x, in-min, in-max, out-min, out-max]
  (+ (quot (* (- x  in-min) (- out-max out-min)) (- in-max in-min)) out-min))

(defn arduino-constrain
  "Clojure implementation of the Arduino constrain function.
  http://arduino.cc/en/Reference/Constrain"
  [x min max]
  (cond (< x min)      min
        (<= min x max) x
        :else          max))

(defn to-voltage
  "Takes an analog value and converts it to the true voltage value."
  [x]
  (* x 0.004882814))

(defn to-hex-str
  "For debug output"
  [x] (str "0x" (.toUpperCase (Integer/toHexString x))))

