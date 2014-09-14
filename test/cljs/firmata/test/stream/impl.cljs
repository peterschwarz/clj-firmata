(ns firmata.test.stream.impl
  (:require [cemerick.cljs.test :as t]
            [firmata.stream.impl :as impl]
            [firmata.test.mock-stream :refer [create-byte-stream]])
  (:require-macros [cemerick.cljs.test
                       :refer (is are deftest testing)]))

(defn ->vec [buffer]
  (if (coll? buffer)
    (vec (map #(->vec %) buffer))
    (vec (aclone buffer))))

(deftest test-preparsing
  (let [emit-buffer (atom [])
        on-complete-data #(swap! emit-buffer conj %)
        preparser (impl/create-preparser on-complete-data)]
      
      (testing "Zero should emit nothing"
        (preparser (create-byte-stream  0))
        (is (= [] @emit-buffer)))

      (reset! emit-buffer [])

      (testing "Basic Version"      
        (preparser (create-byte-stream 0xF9 2 3)) ;version
        (is (= [[0xF9 2 3]]) (->vec @emit-buffer)))

      (reset! emit-buffer [])

      (testing "Multiple messages"
        (preparser (create-byte-stream 0xF9 2 3 0xF0 0x79 2 3 "abc" 0xF7))
        (is (= [[0xF9 2 3]
                [0xF0 0x79 2 3 97 98 99 0xF7]]
               (->vec @emit-buffer))))

      (reset! emit-buffer [])

      (testing "Half-messages"
        ; half firmware
        (preparser (create-byte-stream 0xF0 0x79 2 3))
        (is (= [] @emit-buffer))

        (preparser (create-byte-stream "abc" 0xF7))

        (is (= [[0xF0 0x79 2 3 97 98 99 0xF7]]
               (->vec @emit-buffer))))

      ))

(deftest test-make-buffer
  (testing "basic numbers"
    (are [x y] (= x y)
      0 (aget (impl/make-buffer 0) 0)
      1 (aget (impl/make-buffer 1) 0)))

  (testing "strings"
    (is (= "hello" (.toString (impl/make-buffer "hello")))))

  (testing "vectors"
    (is (= [1 2 97 98 99]
           (->vec (impl/make-buffer [1 2 "abc"]))))))
