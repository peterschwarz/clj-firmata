(ns firmata.test.stream.impl
  (:require [cemerick.cljs.test :as t]
            [firmata.stream.impl :as impl]
            [firmata.test.mock-stream :refer [create-byte-stream]])
  (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var)]))



(defn ->vec [emit-buffer] 
  (vec (map #(vec (aclone %)) @emit-buffer)))

(deftest test-serial-parsing
  (let [parser (impl/create-parser)
        emit-buffer (atom [])]
    (with-redefs [impl/emit! (fn [_ _ data] (swap! emit-buffer conj data))]
      
      (testing "Zero should emit nothing"
        (parser :emitter (create-byte-stream  0))
        (is (= [] @emit-buffer)))

      (reset! emit-buffer [])

      (testing "Basic Version"      
        (parser :emitter (create-byte-stream 0xF9 2 3)) ;version
        (is (= [[0xF9 2 3]]) (->vec emit-buffer)))

      (reset! emit-buffer [])

      (testing "Multiple messages"
        (parser :emitter (create-byte-stream 0xF9 2 3 0xF0 0x79 2 3 "abc" 0xF7))
        (is (= [[0xF9 2 3]
                [0xF0 0x79 2 3 97 98 99 0xF7]]
               (->vec emit-buffer))))

      (reset! emit-buffer [])

      (testing "Half-messages"
        ; half firmware
        (parser :emitter (create-byte-stream 0xF0 0x79 2 3))
        (is (= [] @emit-buffer))

        (parser :emitter (create-byte-stream "abc" 0xF7))

        (is (= [[0xF0 0x79 2 3 97 98 99 0xF7]]
               (->vec emit-buffer))))
        

      )))
