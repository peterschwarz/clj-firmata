(ns firmata.test.core
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go <!! timeout]]
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

(defn mock-serial-listen
  [handler]
  (fn [_ h _]
   (reset! handler h)
   (h (create-in-stream 0xF9 9 9))
   (h (create-in-stream 0xF0 0x79 9 9 "Test Firmware" 0xF7))
   nil))

(deftest test-read-events

  (let [handler (atom nil)]

  (with-redefs [serial/open (fn [name _ rate] {:port name :rate rate})
                serial/listen (mock-serial-listen handler)]

    (let [board    (open-board "some_board")
          evt-chan (event-channel board)]

      (testing "board version initialized with board handshake messages"
        (is (= "9.9" (version board))))

      (testing "board firmware initialized with board handshake messages"
        (is (= {:name "Test Firmware" :version "9.9"} (firmware board))))

      (testing "read protocol version"
        (@handler (create-in-stream 0xF9 2 3))
        (if-let [event (<!! evt-chan)]
              (do
                (is (= :protocol-version (:type event)))
                (is (= "2.3" (:version event))))
              (is (= "Expected event" "but was no event"))
              ))


      (testing "read firmware info"
        (@handler (create-in-stream 0xF0 0x79 2 3 "Firmware Name" 0xF7))
        (if-let [event (<!! evt-chan)]
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
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :capabilities-report (:type event)))
            (is (= {0 {},
                    1 {},
                    2 {:input 1, :output 1, :servo 0x0e},
                    3 {:input 1, :output 1, :pwm 0x08, :servo 0x0e}}
                   (:modes event))))
          (is (= "Expected event" "but was no event"))
          ))

      (testing "empty capabilities"
        (@handler (create-in-stream 0xF0 0x6C 0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :capabilities-report (:type event)))
            (is (= {} (:modes event))))
          (is (= "Expected event" "but was no event"))
          ))

      (testing "read pin state"
        (@handler (create-in-stream 0xF0 0x6E 2 0 0x04 0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :pin-state (:type event)))
            (is (= 2 (:pin event)))
            (is (= :input (:mode event)))
            (is (= 4 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read pin state larger value"
        (@handler (create-in-stream 0xF0 0x6E 2 1 0x7f 0x1 0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :pin-state (:type event)))
            (is (= 2 (:pin event)))
            (is (= :output (:mode event)))
            (is (= 255 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read analog mappings"
        (@handler (create-in-stream 0xF0 0x6A 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0 1 2 3 4 5 0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :analog-mappings (:type event)))
            (is (= {0 14,
                    1 15,
                    2 16,
                    3 17,
                    4 18,
                    5 19} (:mappings event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read i2c-reply"
        (@handler (create-in-stream 0xF0 0x77
                                    0xA 0x0 ; slave address
                                    0x4 0x1 ; register
                                    0x68 0x7 ; data0
                                    0x01 0x0  ; data1
                                    0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :i2c-reply (:type event)))
            (is (= 0x0A (:slave-address event)))
            (is (= 0x84 (:register event)))
            (is (= [1000 1] (:data event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message"
        (@handler (create-in-stream 0x90 1 0))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 0 (:port event)))
            (is (= 0 (:pin event)))
            (is (= :high (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message: low boundary"
        (@handler (create-in-stream 0x90 0 0))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 0 (:pin event)))
            (is (= :low (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message: high boundary"
        (@handler (create-in-stream 0x9F 0x00 0x01))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 127 (:pin event)))
            (is (= :high (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read analog message"
        (@handler (create-in-stream 0xE5 0x68 7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :analog-msg (:type event)))
            (is (= 5 (:pin event)))
            (is (= 1000 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read unknown message"
        (@handler (create-in-stream 0x01))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :unknown-msg (:type event)))
            (is (= 0x01 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read unknown SysEx"
        (@handler (create-in-stream 0xF0 0x01 0x68 0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :unknown-sysex (:type event)))
            (is (= [0x68] (:value event))))
          (is (= "Expected event" "but was no event"))))

    ))))

(deftest test-write
  (let [write-value (atom nil)]
  (with-redefs [serial/open (fn [_ _ _] :port)
                serial/listen (mock-serial-listen (atom nil))
                serial/write (fn [_ x] (reset! write-value x) nil)]
    (let [board (open-board "writable_board")]

      (testing "query protocol version"
        (query-version board)
        (<!! (timeout 10))
        (is (= 0xF9 @write-value)))

      (testing "query firmware"
        (query-firmware board)
        (<!! (timeout 10))
        (is (= [0xF0 0x79 0xF7] @write-value)))

      (testing "query capabilities"
        (query-capabilities board)
        (<!! (timeout 10))
        (is (= [0xF0 0x6B 0xF7] @write-value)))

      (testing "pin state query"
        (query-pin-state board 0)
        (<!! (timeout 10))
        (is (= [0xF0 0x6D 0 0xF7] @write-value))

        (is (thrown? AssertionError (query-pin-state board "foo")))
        (is (thrown? AssertionError (query-pin-state board -1)))
        (is (thrown? AssertionError (query-pin-state board 128))))

      (testing "query analog mappings"
        (query-analog-mappings board)
        (<!! (timeout 10))
        (is (= [0xF0 0x69 0xF7] @write-value)))

      (testing "set pin mode"
        (set-pin-mode board 4 :input)
        (<!! (timeout 10))
        (is (= [0xF4 4 0] @write-value))

        (set-pin-mode board 3 :output)
        (<!! (timeout 10))
        (is (= [0xF4 3 1] @write-value))

        (set-pin-mode board 16 :analog)
        (<!! (timeout 10))
        (is (= [0xF4 16 2] @write-value))

        (set-pin-mode board 13 :pwm)
        (<!! (timeout 10))
        (is (= [0xF4 13 3] @write-value))

        (set-pin-mode board 28 :servo)
        (<!! (timeout 10))
        (is (= [0xF4 28 4] @write-value))

        (is (thrown? AssertionError (set-pin-mode board 1 :foo)))
        (is (thrown? AssertionError (set-pin-mode board "foo" :input)))
        (is (thrown? AssertionError (set-pin-mode board -1 :input)))
        (is (thrown? AssertionError (set-pin-mode board 128 :input))))

      (testing "toggle analog in"
        (enable-analog-in-reporting board 1 true)
        (<!! (timeout 10))
        (is (= [0xC1 1] @write-value))

        (enable-analog-in-reporting board 2 false)
        (<!! (timeout 10))
        (is (= [0xC2 0] @write-value))

        (is (thrown? AssertionError (enable-analog-in-reporting board -1 true)))
        (is (thrown? AssertionError (enable-analog-in-reporting board 16 true))))

      (testing "toggle digital port reporting"
        (enable-digital-port-reporting board 1 true)
        (<!! (timeout 10))
        (is (= [0xD0 1] @write-value))

        (enable-digital-port-reporting board 15 false)
        (<!! (timeout 10))
        (is (= [0xD1 0] @write-value))

        (is (thrown? AssertionError (enable-digital-port-reporting board -1 true)))
        (is (thrown? AssertionError (enable-digital-port-reporting board 16 false))))

      (testing "set digital value"
        (set-digital board 1 :high)
        (<!! (timeout 10))
        (is (= [0x90 0x2 0x0] @write-value))

        (set-digital board 0 :high)
        (<!! (timeout 10))
        (is (= [0x90 0x3 0x0] @write-value))

        (set-digital board 15 :low)
        (<!! (timeout 10))
        (is (= [0x91 0x0 0x0] @write-value))

        (is (thrown? AssertionError (set-digital board 1 :foo)))
        (is (thrown? AssertionError (set-digital board -1 :high)))
        (is (thrown? AssertionError (set-digital board 16 :low))))

      (testing "set analog value"
        (set-analog board 4 1000)
        (is (= [0xE4 0x68 0x7] @write-value))

        (set-analog board 16 1000)
        (<!! (timeout 10))
        (is (= [0xF0 0x6F 16 0x68 0x7 0xF7] @write-value))

        (is (thrown? AssertionError (set-analog board -1 1000)))
        (is (thrown? AssertionError (set-analog board 128 1000))))

      (testing "set sampling interval"
        (set-sampling-interval board 1000)
        (<!! (timeout 10))
        (is (= [0xF0 0x7A 0x68 0x7 0xF7] @write-value)))
    ))))

(deftest test-i2c-messages
  (let [writes (atom [])]
    (with-redefs [serial/open (fn [_ _ _] :port)
                  serial/listen (mock-serial-listen (atom nil))
                  serial/write (fn [_ x] (swap! writes conj x) nil)]
      (let [board (open-board "writable_board")]

        (testing "ic2 request: write"
          (send-i2c-request board 6 :write 0xF 0xE 0xD)
          (<!! (timeout 10))
          (is (= [[0xF0 0x76 6 0 0xF 0x0 0xE 0 0xD 0x0 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 request: read-once"
          (send-i2c-request board 6 :read-once 1000)
          (<!! (timeout 10))
          (is (= [[0xF0 0x76 6 2r0000100 0x68 0x7 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 request: read-continuously"
          (send-i2c-request board 7 :read-continuously)
          (<!! (timeout 10))
          (is (= [[0xF0 0x76 7 2r1000 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 request: stop-reading"
          (send-i2c-request board 7 :stop-reading)
          (<!! (timeout 10))
          (is (= [[0xF0 0x76 7 2r1100 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 config: delay"
          (send-i2c-config board 1000)
          (<!! (timeout 10))
          (is (= [[0xF0 0x78 0x68 0x7 0xF7]] @writes)))

        (reset! writes [])

        (testing "ic2 config: delay and user data"
          (send-i2c-config board 1000 0x10)
          (<!! (timeout 10))
          (is (= [[0xF0 0x78 0x68 0x7 0x10 0xF7]] @writes)))

        (reset! writes [])


  ))))

(deftest test-board-close
  (let [port (atom nil)]
    (with-redefs [serial/open (fn [_ _ _] :port)
                  serial/listen (mock-serial-listen (atom nil))
                  serial/close (fn [p] (reset! port p) nil)]
      (let [board (open-board "writable_board")]

        (close! board)

        (is (= :port @port))))))
