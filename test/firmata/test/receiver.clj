(ns firmata.test.receiver
  (:require [clojure.test :refer :all]
            [firmata.receiver :refer :all]
            [clojure.core.async :refer [<!! >!! chan timeout]]))

(defn- mock-board []
  {:channel (chan)})

(deftest receive-event
    (let [result (atom nil)
          board (mock-board)
          receiver (on-event board #(reset! result %))]
      (>!! (:channel board) {:type :any :value "Foo"})

      (is (= {:type :any :value "Foo"} @result))))

(deftest stop-receiver
  (let [result (atom nil)
          board (mock-board)
          receiver (on-event board #(reset! result %))]
      (stop-receiver! receiver)
      (>!! (:channel board) {:type :any :value "Foo"})

      (is (nil? @result))))

(deftest analog-receiver

  (testing "Only analog events"

    (let [result (atom nil)
          board (mock-board)
          receiver (on-analog-event board 0 #(reset! result %))]
      (>!! (:channel board) {:type :any, :value "Foo"})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! (:channel board) {:type :analog-msg, :pin 1, :value 100})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! (:channel board) {:type :analog-msg, :pin 0, :value 100})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (= {:type :analog-msg, :pin 0, :value 100} @result))))


  (testing "Stop receiver"
    (let [result (atom nil)
          board (mock-board)
          receiver (on-analog-event board 0 #(reset! result %))]
      (stop-receiver! receiver)
      (>!! (:channel board) {:type :analog-msg, :pin 0, :value 100})

      (is (nil? @result))))

  )

(deftest digital-receiver
  (testing "Only digital events"
    (let [result (atom nil)
          board (mock-board)
          receiver (on-digital-event board 0 #(reset! result %))]
      (>!! (:channel board) {:type :any, :value "Foo"})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! (:channel board) {:type :analog-msg, :pin 1, :value 100})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! (:channel board) {:type :digital-msg, :pin 1, :value :high})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (nil? @result))

      (>!! (:channel board) {:type :digital-msg, :pin 0, :value :low})

      (<!! (timeout 100)) ; wait for the go thread to resolve

      (is (= {:type :digital-msg, :pin 0, :value :low} @result))))

   (testing "Stop receiver"
    (let [result (atom nil)
          board (mock-board)
          receiver (on-digital-event board 0 #(reset! result %))]
      (stop-receiver! receiver)
      (>!! (:channel board) {:type :digital-msg, :pin 0, :value :low})

      (is (nil? @result))))
)


(run-tests)
