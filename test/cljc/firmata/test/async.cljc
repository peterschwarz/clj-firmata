(ns firmata.test.async
  (:require #?@(:clj [
                      [clojure.test :as t
                       :refer (is deftest with-test run-tests testing)]
                      [clojure.core.async :as a  :refer [chan pub]]
                      [firmata.async      :as fa :refer [digital-event-chan
                                                         analog-event-chan
                                                         topic-event-chan]]
                      ]
                :cljs [
                       [cemerick.cljs.test :as t]
                       [cljs.core.async    :as a  :refer [chan pub]]
                       [firmata.async      :as fa :refer [digital-event-chan
                                                          analog-event-chan
                                                          topic-event-chan]]]))
  #?(:cljs
     (:require-macros [cemerick.cljs.test
                       :refer (is deftest with-test run-tests testing test-var done)])))

(defn- make-chan []
  (chan (a/sliding-buffer 1)))

(defn- make-exception [msg]
  #?(:clj  (java.lang.RuntimeException. msg))
  #?(:cljs (js/Error. msg)))

(defn- send-msg [board msg]
  (a/put! (:read-ch board) msg))

(defn- mock-board []
  (let [read-ch (make-chan)
        mult-ch (a/mult read-ch)
        e-ch (make-chan)
        pub-ch (make-chan)
        p (pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch e-ch)
    (a/tap mult-ch pub-ch)

    {:read-ch read-ch
     :mult-ch mult-ch
     :create-channel make-chan
     :pub-ch pub-ch
     :publisher p}))

(deftest ^:async test-digital-channal-filter-on-pin
  (let [board (mock-board)
        dig-ch (digital-event-chan board 0)]

      (send-msg board {:type :digital-msg, :pin 1, :value :high})
      (send-msg board {:type :digital-msg, :pin 0, :value :high})

      (a/take! dig-ch (fn [evt]
        (is (= 0 (:pin evt)))
        (is (= :high (:value evt)))

        #?(:cljs (done)) ))))

(deftest ^:async test-digital-channal-filter-on-msg-type
  (let [board (mock-board)
        dig-ch (digital-event-chan board 0)]

      (send-msg board {:type :analog-msg, :pin 0, :value 1023})
      (send-msg board {:type :digital-msg, :pin 0, :value :low})

      (a/take! dig-ch (fn [evt]
        (is (= 0 (:pin evt)))
        (is (= :low (:value evt)))

        #?(:cljs (done)) ))))

(deftest ^:async test-digital-channal-filter-exception
  (let [board (mock-board)
        dig-ch (digital-event-chan board 0)
        exception (make-exception "Test Exception")]

      (send-msg board exception)

      (a/take! dig-ch (fn [evt]
        (is (= exception evt))

        #?(:cljs (done)) ))))

(deftest ^:async test-analog-channal-filter-on-pin
  (let [board (mock-board)
       alg-ch (analog-event-chan board 0)]

      (send-msg board {:type :analog-msg, :pin 1, :value 100})
      (send-msg board {:type :analog-msg, :pin 0, :value 100})

      (a/take! alg-ch (fn [evt]
        (is (= 0 (:pin evt)))
        (is (= 100 (:value evt)))

        #?(:cljs (done)) ))))

(deftest ^:async test-analog-channal-filter-on-msg-type
  (let [board (mock-board)
        alg-ch (analog-event-chan board 0)]

      (send-msg board {:type :digital-msg :pin 0, :value :high})
      (send-msg board {:type :analog-msg, :pin 0, :value 100})

      (a/take! alg-ch (fn [evt]
        (is (= 0 (:pin evt)))
        (is (= 100 (:value evt)))

        #?(:cljs (done)) ))))

(deftest ^:async test-analog-channal-filter-exception
  (let [board (mock-board)
        alg-ch (digital-event-chan board 0)
        exception (make-exception "Test Exception")]

      (send-msg board exception)

      (a/take! alg-ch (fn [evt]
        (is (= exception evt))

        #?(:cljs (done)) ))))

(deftest ^:async test-topic-channel-filter
  (let [board (mock-board)
        topic-ch (topic-event-chan board [:foo nil])]
    (send-msg board {:type :digital-msg :pin 0, :value :high})
    (send-msg board {:type :foo :value :bar})

    #?(:cljs (a/take! topic-ch
                      (fn [evt]
                        (is (= :foo (:type evt)))
                        (done))))
    #?(:clj (let [evt (a/<!! topic-ch)]
              (is (= :foo (:type evt)))))
    ))

(deftest ^:async test-non-vector-topic-channel-filter
  (let [board (mock-board)
        topic-ch (topic-event-chan board :foo)]
    (send-msg board {:type :digital-msg :pin 0, :value :high})
    (send-msg board {:type :foo :value :bar})

    #?(:cljs (a/take! topic-ch
                      (fn [evt]
                        (is (= :foo (:type evt)))
                        (done))))
    #?(:clj (let [evt (a/<!! topic-ch)]
              (is (= :foo (:type evt)))))
    ))
