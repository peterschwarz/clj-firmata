(ns firmata.test.core
  (:require #+clj 
            [clojure.test :as t
                   :refer (is deftest with-test run-tests testing)]
            #+cljs
            [cemerick.cljs.test :as t]
            [clojure.core.async :refer [go <!! timeout alts!!]]
            [serial.core :as serial]
            [firmata.core :refer [open-serial-board event-channel reset-board! 
                                  version close! firmware query-firmware query-capabilities
                                  query-version query-analog-mappings query-pin-state
                                  set-pin-mode enable-analog-in-reporting enable-digital-port-reporting
                                  set-digital set-analog set-sampling-interval]])
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

(defn get-event
  [ch]
  (first (alts!! [ch (timeout 200)])))

(deftest test-read-events

  (let [handler (atom nil)]

  (with-redefs [serial/open (fn [name _ rate] {:port name :rate rate})
                serial/listen (mock-serial-listen handler)]

    (let [board    (open-serial-board "some_board")
          evt-chan (event-channel board)]

      (testing "board version initialized with board handshake messages"
        (is (= "9.9" (version board))))

      (testing "board firmware initialized with board handshake messages"
        (is (= {:name "Test Firmware" :version "9.9"} (firmware board))))

      (testing "read protocol version"
        (@handler (create-in-stream 0xF9 2 3))
        (if-let [event (get-event evt-chan)]
              (do
                (is (= :protocol-version (:type event)))
                (is (= "2.3" (:version event))))
              (is (= "Expected event" "but was no event"))
              ))


      (testing "read firmware info"
        (@handler (create-in-stream 0xF0 0x79 2 3 "Firmware Name" 0xF7))
        (if-let [event (get-event evt-chan)]
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
        (if-let [event (get-event evt-chan)]
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
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :capabilities-report (:type event)))
            (is (= {} (:modes event))))
          (is (= "Expected event" "but was no event"))
          ))

      (testing "read pin state"
        (@handler (create-in-stream 0xF0 0x6E 2 0 0x04 0xF7))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :pin-state (:type event)))
            (is (= 2 (:pin event)))
            (is (= :input (:mode event)))
            (is (= 4 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read pin state larger value"
        (@handler (create-in-stream 0xF0 0x6E 2 1 0x7f 0x1 0xF7))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :pin-state (:type event)))
            (is (= 2 (:pin event)))
            (is (= :output (:mode event)))
            (is (= 255 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read analog mappings"
        (@handler (create-in-stream 0xF0 0x6A 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0 1 2 3 4 5 0xF7))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :analog-mappings (:type event)))
            (is (= {0 14,
                    1 15,
                    2 16,
                    3 17,
                    4 18,
                    5 19} (:mappings event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read string data"
        (@handler (create-in-stream 0xF0 0x71 "Hello World" 0xF7))
        (if-let [event (<!! evt-chan)]
          (do
            (is (= :string-data (:type event)))
            (is (= "Hello World" (:data event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message"
        (@handler (create-in-stream 0x90 1 0))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 0 (:port event)))
            (is (= 0 (:pin event)))
            (is (= :high (:value event)))
            (is (= 1 (:raw-value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message: low boundary"
        (@handler (create-in-stream 0x90 0 0))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 0 (:pin event)))
            (is (= :low (:value event)))
            (is (= 0 (:raw-value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message: high boundary"
        (@handler (create-in-stream 0x9F 0x00 0x01))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 127 (:pin event)))
            (is (= :high (:value event)))
            (is (= 1 (:raw-value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read analog message"
        (@handler (create-in-stream 0xE5 0x68 7))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :analog-msg (:type event)))
            (is (= 5 (:pin event)))
            (is (= 1000 (:value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read unknown message"
        (@handler (create-in-stream 0x01))
        (if-let [event (get-event evt-chan)]
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

(deftest test-read-events-alternate-from-raw

  (let [handler (atom nil)]

  (with-redefs [serial/open (fn [name _ rate] {:port name :rate rate})
                serial/listen (mock-serial-listen handler)]

    (let [board    (open-serial-board "some_board"
                      :from-raw-digital #(if (= 1 %) :foo :bar))
          evt-chan (event-channel board)]

      (testing "read digital message: low boundary"
        (@handler (create-in-stream 0x90 0 0))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= 0 (:pin event)))
            (is (= :bar (:value event)))
            (is (= 0 (:raw-value event))))
          (is (= "Expected event" "but was no event"))))

      (testing "read digital message: high boundary"
        (@handler (create-in-stream 0x9F 0x00 0x01))
        (if-let [event (get-event evt-chan)]
          (do
            (is (= :digital-msg (:type event)))
            (is (= 127 (:pin event)))
            (is (= :foo (:value event)))
            (is (= 1 (:raw-value event))))
          (is (= "Expected event" "but was no event"))))

          ))))

(defn wait-for-it []
  (<!! (timeout 100)))

(deftest test-write
  (let [writes (atom nil)
        last-write (fn [] @writes)]
  (with-redefs [serial/open (fn [_ _ _] :port)
                serial/listen (mock-serial-listen (atom nil))
                serial/write (fn [_ x] (reset! writes x) nil)]
    (let [board (open-serial-board "writable_board")]

      (testing "reset board"
        (reset-board! board)
        (wait-for-it)
        (is (= 0xFF (last-write))))

      (testing "query protocol version"
        (query-version board)
        (wait-for-it)
        (is (= 0xF9 (last-write))))

      (testing "query firmware"
        (query-firmware board)
        (wait-for-it)
        (is (= [0xF0 0x79 0xF7] (last-write))))

      (testing "query capabilities"
        (query-capabilities board)
        (wait-for-it)
        (is (= [0xF0 0x6B 0xF7] (last-write))))

      (testing "pin state query"
        (query-pin-state board 0)
        (wait-for-it)
        (is (= [0xF0 0x6D 0 0xF7] (last-write)))

        (is (thrown? AssertionError (query-pin-state board "foo")))
        (is (thrown? AssertionError (query-pin-state board -1)))
        (is (thrown? AssertionError (query-pin-state board 128))))

      (testing "query analog mappings"
        (query-analog-mappings board)
        (wait-for-it)
        (is (= [0xF0 0x69 0xF7] (last-write))))

      (testing "set pin mode"
        (set-pin-mode board 4 :input)
        (wait-for-it)
        (is (= [0xF4 4 0] (last-write)))

        (set-pin-mode board 3 :output)
        (wait-for-it)
        (is (= [0xF4 3 1] (last-write)))

        (set-pin-mode board 16 :analog)
        (wait-for-it)
        (is (= [0xF4 16 2] (last-write)))

        (set-pin-mode board 13 :pwm)
        (wait-for-it)
        (is (= [0xF4 13 3] (last-write)))

        (set-pin-mode board 28 :servo)
        (wait-for-it)
        (is (= [0xF4 28 4] (last-write)))

        (is (thrown? AssertionError (set-pin-mode board 1 :foo)))
        (is (thrown? AssertionError (set-pin-mode board "foo" :input)))
        (is (thrown? AssertionError (set-pin-mode board -1 :input)))
        (is (thrown? AssertionError (set-pin-mode board 128 :input))))

      (testing "toggle analog in"
        (enable-analog-in-reporting board 1 true)
        (wait-for-it)
        (is (= [0xC1 1] (last-write)))

        (enable-analog-in-reporting board 2 false)
        (wait-for-it)
        (is (= [0xC2 0] (last-write)))

        (is (thrown? AssertionError (enable-analog-in-reporting board -1 true)))
        (is (thrown? AssertionError (enable-analog-in-reporting board 16 true))))

      (testing "toggle digital port reporting"
        (enable-digital-port-reporting board 1 true)
        (wait-for-it)
        (is (= [0xD0 1] (last-write)))

        (enable-digital-port-reporting board 15 false)
        (wait-for-it)
        (is (= [0xD1 0] (last-write)))

        (is (thrown? AssertionError (enable-digital-port-reporting board -1 true)))
        (is (thrown? AssertionError (enable-digital-port-reporting board 16 false))))

      (testing "set digital value: Keyword"
        (set-digital board 1 :high)
        (wait-for-it)
        (is (= [0x90 0x2 0x0] (last-write)))

        (set-digital board 0 :high)
        (wait-for-it)
        (is (= [0x90 0x3 0x0] (last-write)))

        (set-digital board 15 :low)
        (wait-for-it)
        (is (= [0x91 0x0 0x0] (last-write)))

        (is (thrown? AssertionError (set-digital board 1 :foo)))
        (is (thrown? AssertionError (set-digital board -1 :high)))
        (is (thrown? AssertionError (set-digital board 16 :low))))

      (testing "set digital value: Symbol"
        (set-digital board 0 'low)
        (wait-for-it)
        (is (= [0x90 0x2 0x0] (last-write)))

        (set-digital board 0 'high)
        (wait-for-it)
        (is (= [0x90 0x3 0x0] (last-write)))

        (is (thrown? AssertionError (set-digital board 1 'foo)))
        (is (thrown? AssertionError (set-digital board -1 :high)))
        (is (thrown? AssertionError (set-digital board 16 :low))))

      (testing "set digital value: char"
        (set-digital board 0 \0)
        (wait-for-it)
        (is (= [0x90 0x2 0x0] (last-write)))

        (set-digital board 0 \1)
        (wait-for-it)
        (is (= [0x90 0x3 0x0] (last-write)))

        (is (thrown? AssertionError (set-digital board 1 \f)))
        (is (thrown? AssertionError (set-digital board -1 :high)))
        (is (thrown? AssertionError (set-digital board 16 :low))))

      (testing "set digital value: literal"
        (set-digital board 0 0)
        (wait-for-it)
        (is (= [0x90 0x2 0x0] (last-write)))

        (set-digital board 0 1)
        (wait-for-it)
        (is (= [0x90 0x3 0x0] (last-write)))

        (is (thrown? AssertionError (set-digital board 1 23)))
        (is (thrown? AssertionError (set-digital board -1 :high)))
        (is (thrown? AssertionError (set-digital board 16 :low))))

      (testing "set analog value"
        (set-analog board 4 1000)
        (wait-for-it)
        (is (= [0xE4 0x68 0x7] (last-write)))

        (set-analog board 16 1000)
        (wait-for-it)
        (is (= [0xF0 0x6F 16 0x68 0x7 0xF7] (last-write)))

        (is (thrown? AssertionError (set-analog board -1 1000)))
        (is (thrown? AssertionError (set-analog board 128 1000))))

      (testing "set sampling interval"
        (set-sampling-interval board 1000)
        (wait-for-it)
        (is (= [0xF0 0x7A 0x68 0x7 0xF7] (last-write))))
    ))))

(deftest test-board-close
  (let [port (atom nil)]
    (with-redefs [serial/open (fn [_ _ _] :port)
                  serial/listen (mock-serial-listen (atom nil))
                  serial/close (fn [p] (reset! port p) nil)]
      (let [board (open-serial-board "writable_board")]

        (close! board)

        (is (= :port @port))))))
