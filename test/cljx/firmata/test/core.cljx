(ns firmata.test.core
  (:require #+clj 
            [clojure.test :as t
                   :refer (is deftest with-test run-tests testing)]
            #+cljs
            [cemerick.cljs.test :as t]
            [firmata.test.async-helpers :refer [get-event]]
            [firmata.test.mock-stream :refer [create-mock-stream receive-bytes is-open? last-write]]
            [firmata.core :refer [open-board event-channel reset-board! 
                                  version close! firmware query-firmware query-capabilities
                                  query-version query-analog-mappings query-pin-state
                                  set-pin-mode enable-analog-in-reporting enable-digital-port-reporting
                                  set-digital set-analog set-sampling-interval]])
  #+cljs 
  (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var)]))


(deftest test-read-events
  (let [client (create-mock-stream)
        board    (open-board client)
        evt-chan (event-channel board)]

    (testing "board version initialized with board handshake messages"
      (is (= "9.9" (version board))))

    (testing "board firmware initialized with board handshake messages"
      (is (= {:name "Test Firmware" :version "9.9"} (firmware board))))

    (testing "read protocol version"
      (receive-bytes client 0xF9 2 3)
      (get-event evt-chan (fn [event]
        (is (= :protocol-version (:type event)))
        (is (= "2.3" (:version event))))))


    (testing "read firmware info"
      (receive-bytes client 0xF0 0x79 2 3 "Firmware Name" 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :firmware-report (:type event)))
        (is (= "2.3" (:version event)))
        (is (= "Firmware Name" (:name event))))))

    (testing "read capabilities"
      (receive-bytes client 0xF0 0x6C
                                  0x7f ; empty capability 0
                                  0x7f ; empty capability 1
                                  0x00 0x01 0x01 0x01 0x04 0x0e 0x7f
                                  0x00 0x01 0x01 0x01 0x03 0x08 0x04 0x0e 0x7f
                                  0xF7)
      (get-event evt-chan (fn [event]
        (is (= :capabilities-report (:type event)))
        (is (= {0 {},
                1 {},
                2 {:input 1, :output 1, :servo 0x0e},
                3 {:input 1, :output 1, :pwm 0x08, :servo 0x0e}}
               (:modes event))))))

    (testing "empty capabilities"
      (receive-bytes client 0xF0 0x6C 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :capabilities-report (:type event)))
        (is (= {} (:modes event))))))

    (testing "read pin state"
      (receive-bytes client 0xF0 0x6E 2 0 0x04 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :pin-state (:type event)))
        (is (= 2 (:pin event)))
        (is (= :input (:mode event)))
        (is (= 4 (:value event))))))

    (testing "read pin state larger value"
      (receive-bytes client 0xF0 0x6E 2 1 0x7f 0x1 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :pin-state (:type event)))
        (is (= 2 (:pin event)))
        (is (= :output (:mode event)))
        (is (= 255 (:value event))))))

    (testing "read analog mappings"
      (receive-bytes client 0xF0 0x6A 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0x7F 0 1 2 3 4 5 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :analog-mappings (:type event)))
        (is (= {0 14,
                1 15,
                2 16,
                3 17,
                4 18,
                5 19} (:mappings event))))))

    (testing "read string data"
      (receive-bytes client 0xF0 0x71 "Hello World" 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :string-data (:type event)))
        (is (= "Hello World" (:data event))))))

    (testing "read digital message"
      (receive-bytes client 0x90 1 0)
      (get-event evt-chan (fn [event]
          (is (= :digital-msg (:type event)))
          (is (= 0 (:port event)))
          (is (= 0 (:pin event)))
          (is (= :high (:value event)))
          (is (= 1 (:raw-value event))))))

    (testing "read digital message: low boundary"
      (receive-bytes client 0x90 0 0)
      (get-event evt-chan (fn [event]
        (is (= :digital-msg (:type event)))
        (is (= 0 (:pin event)))
        (is (= :low (:value event)))
        (is (= 0 (:raw-value event))))))

    (testing "read digital message: high boundary"
      (receive-bytes client 0x9F 0x00 0x01)
      (get-event evt-chan (fn [event]
        (is (= :digital-msg (:type event)))
        (is (= 127 (:pin event)))
        (is (= :high (:value event)))
        (is (= 1 (:raw-value event))))))

    (testing "read analog message"
      (receive-bytes client 0xE5 0x68 7)
      (get-event evt-chan (fn [event]
        (is (= :analog-msg (:type event)))
        (is (= 5 (:pin event)))
        (is (= 1000 (:value event))))))

    (testing "read unknown message"
      (receive-bytes client 0x01)
      (get-event evt-chan (fn [event]
        (is (= :unknown-msg (:type event)))
        (is (= 0x01 (:value event))))))

    (testing "read unknown SysEx"
      (receive-bytes client 0xF0 0x01 0x68 0xF7)
      (get-event evt-chan (fn [event]
        (is (= :unknown-sysex (:type event)))
        (is (= [0x68] (:value event))))))

  ))

(deftest test-read-events-alternate-from-raw

  (let [client (create-mock-stream)
        board    (open-board client
                    :from-raw-digital #(if (= 1 %) :foo :bar))
        evt-chan (event-channel board)]

    (testing "read digital message: low boundary"
      (receive-bytes client 0x90 0 0)
      (get-event evt-chan (fn [event]
        (is (= 0 (:pin event)))
        (is (= :bar (:value event)))
        (is (= 0 (:raw-value event))))))

    (testing "read digital message: high boundary"
      (receive-bytes client 0x9F 0x00 0x01)
      (get-event evt-chan (fn [event]
        (is (= :digital-msg (:type event)))
        (is (= 127 (:pin event)))
        (is (= :foo (:value event)))
        (is (= 1 (:raw-value event))))))

        ))

(deftest test-write
  (let [client (create-mock-stream)
        board (open-board client)]

    (testing "reset board"
      (reset-board! board)
      (is (= 0xFF (last-write client))))

    (testing "query protocol version"
      (query-version board)
      (is (= 0xF9 (last-write client))))

    (testing "query firmware"
      (query-firmware board)
      (is (= [0xF0 0x79 0xF7] (last-write client))))

    (testing "query capabilities"
      (query-capabilities board)
      (is (= [0xF0 0x6B 0xF7] (last-write client))))

    (testing "pin state query"
      (query-pin-state board 0)
      (is (= [0xF0 0x6D 0 0xF7] (last-write client)))

      (is (thrown? AssertionError (query-pin-state board "foo")))
      (is (thrown? AssertionError (query-pin-state board -1)))
      (is (thrown? AssertionError (query-pin-state board 128))))

    (testing "query analog mappings"
      (query-analog-mappings board)
      (is (= [0xF0 0x69 0xF7] (last-write client))))

    (testing "set pin mode"
      (set-pin-mode board 4 :input)
      (is (= [0xF4 4 0] (last-write client)))

      (set-pin-mode board 3 :output)
      (is (= [0xF4 3 1] (last-write client)))

      (set-pin-mode board 16 :analog)
      (is (= [0xF4 16 2] (last-write client)))

      (set-pin-mode board 13 :pwm)
      (is (= [0xF4 13 3] (last-write client)))

      (set-pin-mode board 28 :servo)
      (is (= [0xF4 28 4] (last-write client)))

      (is (thrown? AssertionError (set-pin-mode board 1 :foo)))
      (is (thrown? AssertionError (set-pin-mode board "foo" :input)))
      (is (thrown? AssertionError (set-pin-mode board -1 :input)))
      (is (thrown? AssertionError (set-pin-mode board 128 :input))))

    (testing "toggle analog in"
      (enable-analog-in-reporting board 1 true)
      (is (= [0xC1 1] (last-write client)))

      (enable-analog-in-reporting board 2 false)
      (is (= [0xC2 0] (last-write client)))

      (is (thrown? AssertionError (enable-analog-in-reporting board -1 true)))
      (is (thrown? AssertionError (enable-analog-in-reporting board 16 true))))

    (testing "toggle digital port reporting"
      (enable-digital-port-reporting board 1 true)
      (is (= [0xD0 1] (last-write client)))

      (enable-digital-port-reporting board 15 false)
      (is (= [0xD1 0] (last-write client)))

      (is (thrown? AssertionError (enable-digital-port-reporting board -1 true)))
      (is (thrown? AssertionError (enable-digital-port-reporting board 16 false))))

    (testing "set digital value: Keyword"
      (set-digital board 1 :high)
      (is (= [0x90 0x2 0x0] (last-write client)))

      (set-digital board 0 :high)
      (is (= [0x90 0x3 0x0] (last-write client)))

      (set-digital board 15 :low)
      (is (= [0x91 0x0 0x0] (last-write client)))

      (is (thrown? AssertionError (set-digital board 1 :foo)))
      (is (thrown? AssertionError (set-digital board -1 :high)))
      (is (thrown? AssertionError (set-digital board 16 :low))))

    (testing "set digital value: Symbol"
      (set-digital board 0 'low)
      (is (= [0x90 0x2 0x0] (last-write client)))

      (set-digital board 0 'high)
      (is (= [0x90 0x3 0x0] (last-write client)))

      (is (thrown? AssertionError (set-digital board 1 'foo)))
      (is (thrown? AssertionError (set-digital board -1 :high)))
      (is (thrown? AssertionError (set-digital board 16 :low))))

    (testing "set digital value: char"
      (set-digital board 0 \0)
      (is (= [0x90 0x2 0x0] (last-write client)))

      (set-digital board 0 \1)
      (is (= [0x90 0x3 0x0] (last-write client)))

      (is (thrown? AssertionError (set-digital board 1 \f)))
      (is (thrown? AssertionError (set-digital board -1 :high)))
      (is (thrown? AssertionError (set-digital board 16 :low))))

    (testing "set digital value: literal"
      (set-digital board 0 0)
      (is (= [0x90 0x2 0x0] (last-write client)))

      (set-digital board 0 1)
      (is (= [0x90 0x3 0x0] (last-write client)))

      (is (thrown? AssertionError (set-digital board 1 23)))
      (is (thrown? AssertionError (set-digital board -1 :high)))
      (is (thrown? AssertionError (set-digital board 16 :low))))

    (testing "set analog value"
      (set-analog board 4 1000)
      (is (= [0xE4 0x68 0x7] (last-write client)))

      (set-analog board 16 1000)
      (is (= [0xF0 0x6F 16 0x68 0x7 0xF7] (last-write client)))

      (is (thrown? AssertionError (set-analog board -1 1000)))
      (is (thrown? AssertionError (set-analog board 128 1000))))

    (testing "set sampling interval"
      (set-sampling-interval board 1000)
      (is (= [0xF0 0x7A 0x68 0x7 0xF7] (last-write client))))
  ))

(deftest test-board-close
  (let [client (create-mock-stream)
        board (open-board client)]

    (close! board)

    (is (not (is-open? client)))))
