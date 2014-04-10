(ns firmata.test.receiver
  (:require [clojure.test :refer :all]
            [firmata.core :refer :all]
            [firmata.receiver :refer :all]
            [clojure.core.async
             :as a
             :refer [<!! >!! chan timeout pub]]))

(defn- make-chan []
  (chan (a/sliding-buffer 1)))

(defn- mock-board [read-ch]
  (let [mult-ch (a/mult read-ch)
        e-ch (make-chan)
        pub-ch (make-chan)
        p (pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch e-ch)
    (a/tap mult-ch pub-ch)

    (reify
      Firmata
      (event-channel [this] read-ch)
      (event-publisher [this] p)
      (release-event-channel [this ch] (a/untap mult-ch ch)))))

(deftest receive-event
  (let [channel (make-chan)
        result (atom nil)
        board (mock-board channel)
        receiver (on-event board #(reset! result %))]
    (>!! channel {:type :any :value "Foo"})

    (<!! (timeout 10))

    (is (= {:type :any :value "Foo"} @result))

    (a/close! channel)))

(deftest stop-receiver
  (let [channel (make-chan)
        result (atom nil)
        board (mock-board channel)
        receiver (on-event board #(reset! result %))]
    (stop-receiver! receiver)
    (>!! channel {:type :any :value "Foo"})

    (is (nil? @result))
    (a/close! channel)))

(deftest analog-receiver

  (testing "Only analog events"

    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-analog-event board 0 #(reset! result %))]
      (>!! channel {:type :any, :value "Foo"})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! channel {:type :analog-msg, :pin 1, :value 100})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! channel {:type :analog-msg, :pin 0, :value 100})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (= {:type :analog-msg, :pin 0, :value 100} @result))
      (a/close! channel)))


  (testing "Stop receiver"
    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-analog-event board 0 #(reset! result %))]
      (stop-receiver! receiver)
      (>!! channel {:type :analog-msg, :pin 0, :value 100})

      (is (nil? @result))

      (a/close! channel)))

  )

(deftest digital-receiver
  (testing "Only digital events"
    (let [channel (make-chan)
          result (atom nil)
          board (mock-board channel)
          receiver (on-digital-event board 0 #(reset! result %))]
      (>!! channel {:type :any, :value "Foo"})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! channel {:type :analog-msg, :pin 1, :value 100})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! channel {:type :digital-msg, :pin 1, :value :high})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! channel {:type :digital-msg, :pin 0, :value :low})

      (<!! (timeout 10)) ; wait for the go thread to resolve

      (is (= {:type :digital-msg, :pin 0, :value :low} @result))

      (a/close! channel)))

   (testing "Stop receiver"
     (let [channel (make-chan)
           result (atom nil)
           board (mock-board channel)
           receiver (on-digital-event board 0 #(reset! result %))]
      (stop-receiver! receiver)
      (println "adding to channel")
      (>!! channel {:type :digital-msg, :pin 0, :value :low})
      (println "added to channel")

      (is (nil? @result))

      (a/close! channel)))
)

