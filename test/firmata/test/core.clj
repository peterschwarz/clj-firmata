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

    (testing "read protocol version"
      (let [board (connect "some_board")
          in (create-in-stream 0xF9 2 3)]
      (@handler in)
      (if-let [event (<!! (:channel board))]
            (do
              (is (= :protocol-version (:type event)))
              (is (= "2.3" (:version event))))
            (is (= "Expected event" "but was no event"))
            )))


    (testing "read firmware info"
      (let [board (connect "some_board")
          in (create-in-stream 0xF0 0x79 2 3 "Firmware Name" 0xF7)]
      (@handler in)
      (if-let [event (<!! (:channel board))]
            (do
              (is (= :firmware-report (:type event)))
              (is (= "2.3" (:version event)))
              (is (= "Firmware Name" (:name event))))
            (is (= "Expected event" "but was no event"))
            )))
    ))



(run-tests)

