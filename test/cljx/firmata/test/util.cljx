(ns firmata.test.util
  (:require #+clj 
            [clojure.test :as t
                   :refer (is are deftest with-test run-tests testing)]
            #+cljs
            [cemerick.cljs.test :as t]
            [firmata.util :refer [to-hex-str arduino-map arduino-constrain lowest-set-bit]])
  #+cljs 
  (:require-macros [cemerick.cljs.test
                       :refer (is are deftest with-test run-tests testing test-var)]))


(deftest to-hex-str-test

  (is (= "0xFF" (to-hex-str 0xff)))
  (is (= "0x0" (to-hex-str 0)))
  (is (= "0x3E8" (to-hex-str 1000))))

(deftest arduino-map-test
  (testing "both ascending ranges"
    (are [x y] (= x y )
         ; lower edge
         0 (arduino-map -1 0 1023 0 255)
         0 (arduino-map 0 0 1023 0 255)

         63  (arduino-map 255 0 1023 0 255)
         127 (arduino-map 511 0 1023 0 255)
         191 (arduino-map 767 0 1023 0 255)

         ; upper edge
         255 (arduino-map 1023 0 1023 0 255)
         255 (arduino-map 1024 0 1023 0 255))
       )

  (testing "from ascending to descending"
    (are [x y] (= x y)
         255 (arduino-map -1 0 1023 255 0)
         255 (arduino-map 0 0 1023 255 0)

         192 (arduino-map 255 0 1023 255 0)
         128 (arduino-map 511 0 1023 255 0)
         64 (arduino-map 767 0 1023 255 0)

         0 (arduino-map 1023 0 1023 255 0)
         0 (arduino-map 1024 0 1023 255 0))))


(deftest arduino-constrain-test
  (are [x y] (= x y)
       0 (arduino-constrain -1 0 255)
       0 (arduino-constrain 0 0 255)

       64 (arduino-constrain 64 0 255)

       255 (arduino-constrain 255 0 255)
       255 (arduino-constrain 256 0 255)))

(deftest lowest-set-bit-test
  (are [x y] (= x y)
    0 (lowest-set-bit 0)
    0 (lowest-set-bit 1)
    1 (lowest-set-bit 2)
    0 (lowest-set-bit 3)
    2 (lowest-set-bit 4)
    0 (lowest-set-bit 5)
    1 (lowest-set-bit 6)))
