(ns firmata.test.util
  (:require [clojure.test :refer :all]
            [firmata.util :refer :all]))

(deftest to-hex-str-test

  (is (= "0xFF" (to-hex-str 0xff)))
  (is (= "0x0" (to-hex-str 0)))
  (is (= "0x3E8" (to-hex-str 1000)))

  )

(run-tests)
