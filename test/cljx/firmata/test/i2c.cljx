(ns firmata.test.i2c
  (:require #+clj 
            [clojure.test :as t
                   :refer (is deftest with-test run-tests testing)]
            #+cljs
            [cemerick.cljs.test :as t]
            #+clj
            [clojure.core.async :refer [go <!! timeout alts!!]]

            [serial.core :as serial]
            [firmata.core :refer [open-serial-board event-channel]]
            [firmata.i2c :refer [send-i2c-request send-i2c-config]])
  #+cljs 
  (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var)])

  #+clj
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

(defn mock-serial-listen
  [handler]
  (fn [_ h _]
   (reset! handler h)
   (h (create-in-stream 0xF9 9 9))
   (h (create-in-stream 0xF0 0x79 9 9 "Test Firmware" 0xF7))
   nil))

(defn wait-for-it []
  (<!! (timeout 100)))

(defn get-event
  [ch]
  (first (alts!! [ch (timeout 200)])))

(deftest test-read-i2c-events

  (let [handler (atom nil)]

  (with-redefs [serial/open (fn [name _ rate] {:port name :rate rate})
                serial/listen (mock-serial-listen handler)]

    (let [board    (open-serial-board "some_board")
          evt-chan (event-channel board)]

      (testing "read i2c-reply"
        (@handler (create-in-stream 0xF0 0x77
                                    0xA 0x0 ; slave address
                                    0x4 0x1 ; register
                                    0x68 0x7 ; data0
                                    0x01 0x0  ; data1
                                    0xF7))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :i2c-reply (:type event)))
            (is (= 0x0A (:slave-address event)))
            (is (= 0x84 (:register event)))
            (is (= [1000 1] (:data event))))
          (is (= "Expected event" "but was no event"))))
      ))))


(deftest test-i2c-messages
  (let [writes (atom [])]
    (with-redefs [serial/open (fn [_ _ _] :port)
                  serial/listen (mock-serial-listen (atom nil))
                  serial/write (fn [_ x] (swap! writes conj x) nil)]
      (let [board (open-serial-board "writable_board")]

        (testing "ic2 request: write"
          (send-i2c-request board 6 :write 0xF 0xE 0xD)
          (wait-for-it)
          (is (= [[0xF0 0x76 6 0 0xF 0x0 0xE 0 0xD 0x0 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 request: read-once"
          (send-i2c-request board 6 :read-once 1000)
          (wait-for-it)
          (is (= [[0xF0 0x76 6 2r0000100 0x68 0x7 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 request: read-continuously"
          (send-i2c-request board 7 :read-continuously)
          (wait-for-it)
          (is (= [[0xF0 0x76 7 2r1000 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 request: stop-reading"
          (send-i2c-request board 7 :stop-reading)
          (wait-for-it)
          (is (= [[0xF0 0x76 7 2r1100 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 config: delay"
          (send-i2c-config board 1000)
          (wait-for-it)
          (is (= [[0xF0 0x78 0x68 0x7 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 config: delay and user data"
          (send-i2c-config board 1000 0x10)
          (wait-for-it)
          (is (= [[0xF0 0x78 0x68 0x7 0x10 0xF7]] @writes)))

        (reset! writes [])
  ))))
