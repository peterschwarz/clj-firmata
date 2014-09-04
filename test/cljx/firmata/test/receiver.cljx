(ns firmata.test.receiver
  (:require #+clj 
            [clojure.test :as t
                   :refer (is deftest with-test run-tests testing)]
            #+cljs
            [cemerick.cljs.test :as t]
            [firmata.core :refer [event-channel event-publisher release-event-channel Firmata]]
            [firmata.receiver :refer [stop-receiver! on-event on-analog-event on-digital-event]]
            #+clj
            [clojure.core.async
             :as a
             :refer [<!! >!! chan timeout pub]])
  #+cljs 
  (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var)]))

(defn- make-chan []
  (chan (a/sliding-buffer 1)))

(defn- send-msg [ch msg]
  (is (first(a/alts!! [[ch msg]
             (timeout 100)]))))

(defn- mock-board [read-ch]
  (let [mult-ch (a/mult read-ch)
        e-ch (make-chan)
        pub-ch (make-chan)
        p (pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch e-ch)
    (a/tap mult-ch pub-ch)

    (reify
      Firmata
      (event-channel [this] e-ch)
      (event-publisher [this] p)
      (release-event-channel [this ch] (a/untap mult-ch ch)))))

(deftest receive-event
  (let [channel (make-chan)
        result (atom nil)
        board (mock-board channel)
        receiver (on-event board #(reset! result %))]
    (send-msg channel {:type :any :value "Foo"})

    (<!! (timeout 10))

    (is (= {:type :any :value "Foo"} @result))

    (a/close! channel)))

(deftest stop-receiver
  (let [channel (make-chan)
        result (atom nil)
        board (mock-board channel)
        receiver (on-event board #(reset! result %))]
    (stop-receiver! receiver)
    (send-msg channel {:type :any :value "Foo"})

    (is (nil? @result))
    (a/close! channel)))

(deftest analog-receiver

  (testing "Only analog events"

    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-analog-event board 0 #(reset! result %))]
      (send-msg channel {:type :any, :value "Foo"})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (send-msg channel {:type :analog-msg, :pin 1, :value 100})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (send-msg channel {:type :analog-msg, :pin 0, :value 100})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (= {:type :analog-msg, :pin 0, :value 100} @result))
      (a/close! channel)))


  (testing "Stop receiver"
    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-analog-event board 0 #(reset! result %))]
      (stop-receiver! receiver)
      (send-msg channel {:type :analog-msg, :pin 0, :value 100})

      (is (nil? @result))

      (a/close! channel)))

  )

(deftest digital-receiver
  (testing "Only digital events"
    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-digital-event board 0 #(reset! result %))]
      (send-msg channel {:type :any, :value "Foo"})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (send-msg channel {:type :analog-msg, :pin 1, :value 100})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (send-msg channel {:type :digital-msg, :pin 1, :value :high})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (send-msg channel {:type :digital-msg, :pin 0, :value :low})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (= {:type :digital-msg, :pin 0, :value :low} @result))

      (a/close! channel)))

   (testing "Stop receiver"
     (let [channel (make-chan)
           result (atom nil)
           board (mock-board channel)
           receiver (on-digital-event board 0 #(reset! result %))]
       (stop-receiver! receiver)

       (send-msg channel {:type :digital-msg, :pin 0, :value :low})

       (is (nil? @result))

       (a/close! channel)))
)

