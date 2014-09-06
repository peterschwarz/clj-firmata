(ns firmata.util
  #+clj (:require [serial.core :refer [port-ids]]))

; Number conversions

(defn lsb "Least significant byte"
  [x]
  (bit-and x 0x7F))

(defn msb "Most significant byte (of a 16-bit value)"
  [x]
  (bit-and (bit-shift-right x 7) 0x7F))

(defn to-number
  "Converts a sequence of bytes into an (long) number."
  [values]
  (reduce #(bit-or (bit-shift-left %1 7) (bit-and %2 0x7f)) 0 values))

(defn bytes-to-int
  [lsb msb]
  (to-number [msb lsb]))

(defn lowest-set-bit [x]
  #+clj  (int (max 0 (/ (Math/log (bit-and x (- x))) (Math/log 2))))
  #+cljs (/ (.log js/Math (bit-and pin-change (- pin-change))) (aget js/Math "LN2")))

(defn consume-until
  "Consumes bytes from the given input stream until the end-signal is reached."
  [end-signal in initial accumulator]
  (loop [current-value (.read in)
         result initial]
    (if (= end-signal current-value)
      result
      (recur (.read in)
             (accumulator result current-value)))))

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
  [x] (str "0x" (.toUpperCase 
                #+clj (Integer/toHexString x)
                #+cljs (.toString x 16))))

(defn- substring? [sub st]
  (not= (.indexOf st sub) -1))

(defn- arduino-port?
  "Compares port name with known arduino port formats"
  [port-name]
  (or
    (substring? "tty.usbmodem" port-name)    ;; Uno or Mega 2560
    (substring? "tty.usbserial" port-name))) ;; Older boards

#+clj
(defn detect-arduino-port
  "Returns the first arduino serial port based on port
   name, or nil. Currently only works for Mac."
  []
  (first (filter arduino-port?
                 (map #(.getName %) (port-ids)))))
