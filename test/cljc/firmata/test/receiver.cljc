(ns firmata.test.receiver
  (:require #?(:clj
               [clojure.test :as t
                :refer (is deftest with-test run-tests testing)])
            #?(:cljs
               [cemerick.cljs.test :as t])
            [firmata.test.async-helpers :refer [wait-for-it]]
            [firmata.core :refer [event-channel event-publisher release-event-channel]]
            [firmata.receiver :refer [stop-receiver! on-event on-analog-event on-digital-event]]
            #?(:clj
               [clojure.core.async :as a :refer [chan pub]])
            #?(:cljs
               [cljs.core.async    :as a :refer [chan pub]]))

  #?(:cljs
     (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var)])))

(defn- make-chan []
  (chan (a/sliding-buffer 1)))

(defn- send-msg [ch msg]
  (a/put! ch msg))

(defn- mock-board [read-ch]
  (let [mult-ch (a/mult read-ch)
        e-ch (make-chan)
        pub-ch (make-chan)
        p (pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch e-ch)
    (a/tap mult-ch pub-ch)

    {:mult-ch mult-ch
     :create-channel make-chan
     :pub-ch pub-ch
     :publisher p}))

(deftest ^:async receive-event
  (let [channel (make-chan)
        result (atom nil)
        board (mock-board channel)
        receiver (on-event board #(reset! result %))]
    (send-msg channel {:type :any :value "Foo"})

    (wait-for-it 10 (fn []
      (is (= {:type :any :value "Foo"} @result))))

    (a/close! channel)))

(deftest ^:async stop-receiver
  (let [channel (make-chan)
        result (atom nil)
        board (mock-board channel)
        receiver (on-event board #(reset! result %))]
    (stop-receiver! receiver)
    (send-msg channel {:type :any :value "Foo"})

    (is (nil? @result))
    (a/close! channel)))

(deftest ^:async analog-receiver

  (testing "Only analog events"

    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-analog-event board 0 #(reset! result %))]
      (send-msg channel {:type :any, :value "Foo"})

      (wait-for-it 10 (fn []
        (is (nil? @result))))

      (send-msg channel {:type :analog-msg, :pin 1, :value 100})

      (wait-for-it 10 (fn []
        (is (nil? @result))))

      (send-msg channel {:type :analog-msg, :pin 0, :value 100})

      (wait-for-it 10 (fn []
        (is (= {:type :analog-msg, :pin 0, :value 100} @result))))

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

(deftest ^:async digital-receiver
  (testing "Only digital events"
    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-digital-event board 0 #(reset! result %))]
      (send-msg channel {:type :any, :value "Foo"})

      (wait-for-it 10 (fn []
        (is (nil? @result))))

      (send-msg channel {:type :analog-msg, :pin 1, :value 100})

      (wait-for-it 10 (fn []
        (is (nil? @result))))

      (send-msg channel {:type :digital-msg, :pin 1, :value :high})

      (wait-for-it 10 (fn []
        (is (nil? @result))))

      (send-msg channel {:type :digital-msg, :pin 0, :value :low})

      (wait-for-it 10 (fn []
        (is (= {:type :digital-msg, :pin 0, :value :low} @result))))

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
