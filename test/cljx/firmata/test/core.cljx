(ns firmata.test.core
  (:require #+clj 
            [clojure.test :as t
                   :refer (is deftest with-test run-tests testing)]
            #+cljs
            [cemerick.cljs.test :as t]
            #+cljs
            [cljs.nodejs :as nodejs]
            [firmata.test.async-helpers :refer [get-event wait-for-it]]
            [firmata.test.mock-stream :refer [create-mock-stream receive-bytes is-open? last-write 
                                              #+clj throw-on-read throw-on-write]]
            [firmata.test.board-helpers :refer [with-open-board with-serial-board]]
            [firmata.core :refer [open-board event-channel reset-board
                                  version close! firmware query-firmware query-capabilities
                                  query-version query-analog-mappings query-pin-state
                                  set-pin-mode enable-analog-in-reporting enable-digital-port-reporting
                                  set-digital set-analog set-sampling-interval
                                  format-raw-digital]]
            [firmata.util :refer [detect-arduino-port]]
            [firmata.stream :as st])
  #+cljs 
  (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var done)]))


(deftest ^:async test-read-events
  (let [client (create-mock-stream)]
    (with-open-board client (fn [board]
      (let [evt-chan (event-channel board)]

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

 )))))


#+clj
(deftest test-exception-read-events

  (let [client (create-mock-stream)]
    (with-open-board client (fn [board]
      (let [evt-chan (event-channel board)
            exception (java.lang.RuntimeException. "Test Exception")]
        (throw-on-read client exception)

        (get-event evt-chan (fn [event]
          (is (= exception event))
          
          )))))))

(deftest ^:async test-exception-write-events
  (let [client (create-mock-stream)]
    (with-open-board client (fn [board]
      (let [evt-chan (event-channel board)
            exception 
              #+clj  (java.lang.RuntimeException. "Test Exception")
              #+cljs (js/Error. "Test Exception")]

        (throw-on-write client exception)

        ; send any message
        (reset-board board)

        (wait-for-it #(get-event evt-chan (fn [event]
          (is (= exception event))
          #+cljs (done)
          ))))))))

(deftest ^:async test-read-events-highlow-raw

  (let [client (create-mock-stream)]
    (with-open-board client [:digital-result-format :raw] (fn [board]
      (let [evt-chan (event-channel board)]

    (testing "read digital message: low boundary"
      (receive-bytes client 0x90 0 0)
      (get-event evt-chan (fn [event]
        (is (= 0 (:pin event)))
        (is (= 0 (:value event)))
        (is (= 0 (:raw-value event))))))

    (testing "read digital message: high boundary"
      (receive-bytes client 0x9F 0x00 0x01)
      (get-event evt-chan (fn [event]
        (is (= :digital-msg (:type event)))
        (is (= 127 (:pin event)))
        (is (= 1 (:value event)))
        (is (= 1 (:raw-value event))))))

       )))))

(deftest test-format-raw-digital

  (testing "keyword"
    (is (= :low (format-raw-digital :keyword 0)))
    (is (= :high (format-raw-digital :keyword 1))))

  (testing "raw"
    (is (= 0 (format-raw-digital :raw 0)))
    (is (= 1 (format-raw-digital :raw 1))))

  (testing "boolean"
    (is (= false (format-raw-digital :boolean 0)))
    (is (= true (format-raw-digital :boolean 1))))

  (testing "symbol"
    (is (= 'low (format-raw-digital :symbol 0)))
    (is (= 'high (format-raw-digital :symbol 1))))

  (testing "char"
    (is (= \0 (format-raw-digital :char 0)))
    (is (= \1 (format-raw-digital :char 1)))))

(deftest ^:async test-read-events-alternate-from-raw

  (let [client (create-mock-stream)]
    (with-open-board client [:from-raw-digital #(if (= 1 %) :foo :bar)] (fn [board]
      (let [evt-chan (event-channel board)]

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

       )))))

(deftest ^:async test-write
  (let [client (create-mock-stream)]
    (with-open-board client (fn [board]

    (testing "reset board"
      (reset-board board)
      (wait-for-it (fn []
        (is (= [0xFF] (last-write client))))))

    (testing "query protocol version"
      (query-version board)
      (wait-for-it (fn []
        (is (= [0xF9] (last-write client))))))

    (testing "query firmware"
      (query-firmware board)
      (wait-for-it (fn []
        (is (= [0xF0 0x79 0xF7] (last-write client))))))

    (testing "query capabilities"
      (query-capabilities board)
      (wait-for-it (fn []
        (is (= [0xF0 0x6B 0xF7] (last-write client))))))

    (testing "pin state query"
      (query-pin-state board 0)
      (wait-for-it (fn []
        (is (= [0xF0 0x6D 0 0xF7] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (query-pin-state board "foo")))
      (is (thrown? #+clj AssertionError #+cljs js/Error (query-pin-state board -1)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (query-pin-state board 128))))

    (testing "query analog mappings"
      (query-analog-mappings board)
      (wait-for-it (fn []
        (is (= [0xF0 0x69 0xF7] (last-write client))))))

    (testing "set pin mode"
      (set-pin-mode board 4 :input)
      (wait-for-it (fn []
        (is (= [0xF4 4 0] (last-write client)))))

      (set-pin-mode board 3 :output)
      (wait-for-it (fn []
        (is (= [0xF4 3 1] (last-write client)))))

      (set-pin-mode board 16 :analog)
      (wait-for-it (fn []
        (is (= [0xF4 16 2] (last-write client)))))

      (set-pin-mode board 13 :pwm)
      (wait-for-it (fn []
        (is (= [0xF4 13 3] (last-write client)))))

      (set-pin-mode board 28 :servo)
      (wait-for-it (fn []
        (is (= [0xF4 28 4] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (set-pin-mode board 1 :foo)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-pin-mode board "foo" :input)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-pin-mode board -1 :input)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-pin-mode board 128 :input))))

    (testing "toggle analog in"
      (enable-analog-in-reporting board 1 true)
      (wait-for-it (fn []
        (is (= [0xC1 1] (last-write client)))))

      (enable-analog-in-reporting board 2 false)
      (wait-for-it (fn []
        (is (= [0xC2 0] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (enable-analog-in-reporting board -1 true)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (enable-analog-in-reporting board 16 true))))

    (testing "toggle digital port reporting"
      (enable-digital-port-reporting board 1 true)
      (wait-for-it (fn []
        (is (= [0xD0 1] (last-write client)))))

      (enable-digital-port-reporting board 15 false)
      (wait-for-it (fn []
        (is (= [0xD1 0] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (enable-digital-port-reporting board -1 true)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (enable-digital-port-reporting board 16 false))))

    (testing "set digital value: Keyword"
      (set-digital board 1 :high)
      (wait-for-it (fn []
        (is (= [0x90 0x2 0x0] (last-write client)))))

      (set-digital board 0 :high)
      (wait-for-it (fn []
        (is (= [0x90 0x3 0x0] (last-write client)))))

      (set-digital board 15 :low)
      (wait-for-it (fn []
        (is (= [0x91 0x0 0x0] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 1 :foo)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board -1 :high)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 16 :low))))

    (testing "set digital value: Symbol"
      (set-digital board 0 'low)
      (wait-for-it (fn []
        (is (= [0x90 0x2 0x0] (last-write client)))))

      (set-digital board 0 'high)
      (wait-for-it (fn []
        (is (= [0x90 0x3 0x0] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 1 'foo)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board -1 :high)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 16 :low))))

    (testing "set digital value: char"
      (set-digital board 0 \0)
      (wait-for-it (fn []
        (is (= [0x90 0x2 0x0] (last-write client)))))

      (set-digital board 0 \1)
      (wait-for-it (fn []
        (is (= [0x90 0x3 0x0] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 1 \f)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board -1 :high)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 16 :low))))

    (testing "set digital value: literal"
      (set-digital board 0 0)
      (wait-for-it (fn []
        (is (= [0x90 0x2 0x0] (last-write client)))))

      (set-digital board 0 1)
      (wait-for-it (fn []
        (is (= [0x90 0x3 0x0] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 1 23)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board -1 :high)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-digital board 16 :low))))

    (testing "set analog value"
      (set-analog board 4 1000)
      (wait-for-it (fn []
        (is (= [0xE4 0x68 0x7] (last-write client)))))

      (set-analog board 16 1000)
      (wait-for-it (fn []
        (is (= [0xF0 0x6F 16 0x68 0x7 0xF7] (last-write client)))))

      (is (thrown? #+clj AssertionError #+cljs js/Error (set-analog board -1 1000)))
      (is (thrown? #+clj AssertionError #+cljs js/Error (set-analog board 128 1000))))

    (testing "set sampling interval"
      (set-sampling-interval board 1000)
      (wait-for-it (fn []
        (is (= [0xF0 0x7A 0x68 0x7 0xF7] (last-write client))))))
 ))))

(deftest ^:async test-board-close
  (let [client (create-mock-stream)]
    (with-open-board client (fn [board]

      (close! board)

      (is (not (is-open? client)))
      #+cljs (done) ))))

(deftest ^:async test-reset-on-connect
  (let [client (create-mock-stream)]
    (with-open-board client [:reset-on-connect? true] (fn [board]

      (is (= [0xFF] (last-write client)))
       
      #+cljs (done) ))))

(deftest ^:async test-open-serial-with-auto-detect 
  (let [opened-port (atom nil)]
    (with-redefs [detect-arduino-port #+clj  (fn [] "some-port-value")
                                      #+cljs (fn [callback] (callback "some-port-value"))
                  st/create-serial-stream #+clj  (fn [port-name _]
                                                    (reset! opened-port port-name)
                                                    (create-mock-stream))
                                          #+cljs (fn [port-name _ callback]
                                                    (reset! opened-port port-name)
                                                    (callback (create-mock-stream)))]
      (with-serial-board :auto-detect (fn [board]

        (is (= "some-port-value" @opened-port))

        #+cljs (done)
      )))))

(deftest ^:async test-open-serial-port-name 
  (let [opened-port (atom nil)]
    (with-redefs [st/create-serial-stream #+clj  (fn [port-name _]
                                                  (reset! opened-port port-name)
                                                  (create-mock-stream))
                                          #+cljs (fn [port-name _ callback]
                                                  (reset! opened-port port-name)
                                                  (callback (create-mock-stream)))]
      (with-serial-board "cu.usbmodemfa141" (fn [board]

        (is (= "cu.usbmodemfa141" @opened-port))

        #+cljs (done)
      )))))

#+cljs 
(do 
  (nodejs/enable-util-print!)
  (set! *main-cli-fn* #(println "Running tests...")))
