(ns firmata.test.core
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go <!!]]
            [serial.core :as serial]
            [firmata.core :refer :all]
            )
  (:import [java.io ByteArrayInputStream]
           [java.nio ByteBuffer]))

(defprotocol Bytable
  (to-bytes [this] "Converts the type to bytes"))

(extend-protocol Bytable
  Number
  (to-bytes [this] (byte-array 1 (.byteValue this)))

  String
  (to-bytes [this] (.getBytes this "ASCII")))

(defn create-in-stream
  [& more]
  (let [buffer (ByteBuffer/allocate 256)]
    (reduce (fn [^ByteBuffer b ^bytes value] (.put b (to-bytes value))) buffer more)
    (ByteArrayInputStream. (.array buffer))))


(def handler (atom nil))

(deftest test-read-events
  (with-redefs [serial/open (fn [name rate] :port)
                serial/listen (fn [port h skip?] (reset! handler h))]

    (let [board (open-board "some_board")]

      (testing "read protocol version"
        (@handler (create-in-stream 0xF9 2 3))
        (if-let [event (<!! (:channel board))]
              (do
                (is (= :protocol-version (:type event)))
                (is (= "2.3" (:version event))))
              (is (= "Expected event" "but was no event"))
              ))


      (testing "read firmware info"
        (@handler (create-in-stream 0xF0 0x79 2 3 "Firmware Name" 0xF7))
        (if-let [event (<!! (:channel board))]
          (do
            (is (= :firmware-report (:type event)))
            (is (= "2.3" (:version event)))
            (is (= "Firmware Name" (:name event))))
          (is (= "Expected event" "but was no event"))
          ))

      (testing "read capabilities"
        (@handler (create-in-stream 0xF0 0x6C
                                    0x7f ; empty capability 0
                                    0x7f ; empty capability 1
                                    0x00 0x01 0x01 0x01 0x04 0x0e 0x7f
                                    0x00 0x01 0x01 0x01 0x03 0x08 0x04 0x0e 0x7f
                                    0xF7))
        (if-let [event (<!! (:channel board))]
          (do
            (is (= :capabilities-report (:type event)))
            (is (= {0 {},
                    1 {},
                    2 {0 1, 1 1, 4 0x0e},
                    3 {0 1, 1 1, 3 0x08, 4 0x0e}}
                   (:modes event))))
          (is (= "Expected event" "but was no event"))
          ))

      (testing "empty capabilities"
        (@handler (create-in-stream 0xF0 0x6C 0xF7))
        (if-let [event (<!! (:channel board))]
          (do
            (is (= :capabilities-report (:type event)))
            (is (= {} (:modes event))))
          (is (= "Expected event" "but was no event"))
          ))

      (testing "read pin state"
        (@handler (create-in-stream 0xF0 0x6E 2 0 0x04 0xF7))
        (if-let [event (<!! (:channel board))]
          (do
            (is (= :pin-state (:type event)))
            (is (= 2 (:pin event)))
            (is (= 0 (:mode event)))
            (is (= 4 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read pin state larger value"
        (@handler (create-in-stream 0xF0 0x6E 2 0 0x01 0x04 0xF7))
        (if-let [event (<!! (:channel board))]
          (do
            (is (= :pin-state (:type event)))
            (is (= 2 (:pin event)))
            (is (= 0 (:mode event)))
            (is (= 260 (:value event))))
          (is (= "Expected event" "but was no event"))))

    )))

(def write-value (atom nil))

(deftest test-write
  (with-redefs [serial/open (fn [name rate] :port)
                serial/listen (fn [port h skip?] nil)
                serial/write (fn [port x] (reset! write-value x) nil)]
    (let [board (open-board "writable_board")]

      (testing "query protocol version"
        (query-version board)

        (is (= 0xF9 @write-value)))

      (testing "query firmware"
        (query-firmware board)
        (is (= [0xF0 0x79 0xF7] @write-value)))

      (testing "query capabilities"
        (query-capabilities board)
        (is (= [0xF0 0x6B 0xF7] @write-value)))

      (testing "pin state query"
        (query-pin-state board 0)
        (is (= [0xF0 0x6D 0 0xF7] @write-value))

        (is (thrown? AssertionError (query-pin-state board "foo")))
        (is (thrown? AssertionError (query-pin-state board -1)))
        (is (thrown? AssertionError (query-pin-state board 128))))

      (testing "set pin mode"
        (set-pin-mode board 4 :input)
        (is (= [0xF4 4 0] @write-value))

        (set-pin-mode board 3 :output)
        (is (= [0xF4 3 1] @write-value))

        (set-pin-mode board 16 :analog)
        (is (= [0xF4 16 2] @write-value))

        (set-pin-mode board 13 :pwm)
        (is (= [0xF4 13 3] @write-value))

        (set-pin-mode board 28 :servo)
        (is (= [0xF4 28 4] @write-value))

        (is (thrown? AssertionError (set-pin-mode board 1 :foo)))
        (is (thrown? AssertionError (set-pin-mode board "foo" :input)))
        (is (thrown? AssertionError (set-pin-mode board -1 :input)))
        (is (thrown? AssertionError (set-pin-mode board 128 :input))))



    )))


(run-tests)

