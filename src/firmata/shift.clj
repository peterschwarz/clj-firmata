(ns firmata.shift
  (:require [firmata.core :refer [set-digital]]))

(defn shift-out
  "Sends a shift out to the board, for sending values to a shift register"
  [board latch-pin data-pin clock-pin bit-order value]
  {:pre [(or (= bit-order :lsb-first) (= bit-order :msb-first))]}

  (doseq [i (range 8)]
    (let [shift-by (if (= :lsb-first bit-order) i (- 7 i))]
      (set-digital board data-pin (if (= 0 (bit-and value (bit-shift-left 1 shift-by))) :low :high))))

  (set-digital board clock-pin :high)
  (set-digital board clock-pin :low)

  (set-digital board latch-pin :high)
  (set-digital board latch-pin :low)

  board)
