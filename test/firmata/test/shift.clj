(ns firmata.test.shift
  (:require [clojure.test :refer :all]
            [firmata.core :refer [set-digital]]
            [firmata.shift :refer :all]))

(deftest shift-out-test

  (let [writes (atom [])
        latch-pin 4
        data-pin 5
        clock-pin 6]
    (with-redefs [set-digital (fn [_ pin value] (swap! writes conj {:pin pin :value value}))]

      (testing "shift out least significant bit first"

        (shift-out :mock-board latch-pin data-pin clock-pin :lsb-first 0x1)

        (is (= @writes [{:pin data-pin :value :high}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin clock-pin :value :high}
                        {:pin clock-pin :value :low}
                        {:pin latch-pin :value :high}
                        {:pin latch-pin :value :low}])))

      (reset! writes [])

      (testing "shift out least significant bit first - set all high"

        (shift-out :mock-board latch-pin data-pin clock-pin :lsb-first 0xFF)

        (is (= @writes [{:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin clock-pin :value :high}
                        {:pin clock-pin :value :low}
                        {:pin latch-pin :value :high}
                        {:pin latch-pin :value :low}])))

      (reset! writes [])

      (testing "shift out most significant bit first"

        (shift-out :mock-board latch-pin data-pin clock-pin :msb-first 0x1)

        (is (= @writes [{:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :low}
                        {:pin data-pin :value :high}
                        {:pin clock-pin :value :high}
                        {:pin clock-pin :value :low}
                        {:pin latch-pin :value :high}
                        {:pin latch-pin :value :low}])))

      (reset! writes [])

      (testing "shift out least significant bit first - set all high"

        (shift-out :mock-board latch-pin data-pin clock-pin :msb-first 0xFF)

        (is (= @writes [{:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin data-pin :value :high}
                        {:pin clock-pin :value :high}
                        {:pin clock-pin :value :low}
                        {:pin latch-pin :value :high}
                        {:pin latch-pin :value :low}])))

      (testing "bad endian-ness"
        (is (thrown? AssertionError (shift-out :mock-board latch-pin data-pin clock-pin :whatever 0xFF))))

      ))



  )
