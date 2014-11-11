(ns firmata.core
  (:require [firmata.messages :as m]
            [firmata.stream :as st]
            [firmata.stream.spi :as spi :refer [read!]]
            [firmata.sysex :refer [read-sysex-event]]
            [firmata.util :as util :refer [lsb msb]]

            #+clj 
            [clojure.core.async :as a :refer [go chan >! <! <!!]]

            #+cljs
            [cljs.core.async    :as a :refer [chan >! <!]])
  #+cljs
  (:require-macros [cljs.core.async.macros :refer [go]]))

; Pin Modes
(def ^{:private true} mode-values (zipmap m/modes (range 0 (count m/modes))))

(def ^{:private true} MAX-PORTS 16)

(defrecord ^:private Board [stream board-state read-ch write-ch mult-ch pub-ch publisher create-channel])

(defn- read-version
  [in]
  (str (read! in) "." (read! in)))

(defn- read-analog
  [message in]
  (let [pin (- message m/ANALOG_IO_MESSAGE)
        value (util/bytes-to-int (read! in) (read! in))]
    {:type :analog-msg
     :pin pin
     :value value}))

(defn- read-digital
  [board message in]
  (let [port (- message m/DIGITAL_IO_MESSAGE)
        previous-port (get-in @(:state board)[:digital-in port])
        updated-port (util/bytes-to-int (read! in) (read! in))
        pin-change (- updated-port previous-port)
        pin (+ (util/lowest-set-bit pin-change) (* 8 port))
        raw-value (if (> pin-change 0) 1 0)]
    (swap! (:state board) assoc-in [:digital-in port] updated-port)
    {:type :digital-msg
     :port port
     :pin pin
     :value ((:from-raw-digital board) raw-value)
     :raw-value raw-value}))

(defn- read-event
  [board in]
  (let [message (read! in)]
    (cond
     (= m/PROTOCOL_VERSION message) (let [version (read-version in)]
                                    {:type :protocol-version, :version version})
     (= m/SYSEX_START message) (read-sysex-event in)
     (m/is-digital? message) (read-digital board message in)
     (m/is-analog? message)  (read-analog message in)

     :else {:type :unknown-msg
            :value message})))

(defn- firmata-handler
  [board]
  (fn [in]
    (let [event (read-event board in)]
      (go (>! (:channel board) event)))))

(defn- pin?
  ([pin] (pin? pin 128))
  ([pin pin-count]
   (and (number? pin) (>= pin 0) (< pin pin-count))))

(defn- port-of
  "Returns the port of the given pin"
  [pin]
  (bit-and (bit-shift-right pin 3) 0x0F))

(defn- pin-command
  [command pin]
  (bit-and 0xff (bit-or command pin)))

(defn- take-with-timeout!
  [ch default millis f]
  (go 
    (let [x (or (first (a/alts! [ch (a/timeout millis)])) default)]
      (f x))))

(defn- high-low-value 
  "Takes the possible input values from the set [:high :low 1 0 'high 'low \\1 \\0]
  and converts it to a digital value."
  [value]
  (assert (some #(= value %) [:high :low 1 0 'high 'low "1" "0" \1 \0])
    "value must be from the set #{:high :low 1 0 'high 'low  \\1 \\0}")
  (condp = value
    1     :high
    'high :high
    \1    :high
    0     :low
    'low  :low
    \0    :low
    value))

(defn to-keyword 
  "Converts the raw digital values to keywords high or low."
  [raw-value] (if (= 1 raw-value) :high :low))

(defn send-message
  "Sends an arbitrary message to the board.  Useful for I2C message,
   which have user-defined data"
  [board data]
  (a/put! (:write-ch board) data)
  board)

(defn close!
  "Closes the connection to the board."
 [board]
 (a/close! (:write-ch board))
 (a/close! (:read-ch board))
 (spi/close! (:stream board))
 nil)

(defn reset-board
  "Sends the reset signal to the board"
  [board]
  (send-message board m/SYSTEM_RESET))

(defn version
  "Returns the currently known version of the board"
  [board]
  (:board-version (deref (:board-state board))))

(defn firmware
  "Returns the currently known firmware information"
  [board]
  (:board-firmware (deref (:board-state board))))

(defn query-firmware
  "Query the firmware of the board."
  [board]
  (send-message board [m/SYSEX_START m/REPORT_FIRMWARE m/SYSEX_END]))

(defn query-capabilities
  "Query the capabilities of the board."
  [board]
  (send-message board [m/SYSEX_START m/CAPABILITY_QUERY m/SYSEX_END]))

(defn query-version
  "Query the firmware version of the board."
  [board]
  (send-message board m/PROTOCOL_VERSION))

(defn query-analog-mappings
  "Analog messages are numbered 0 to 15, which traditionally refer to the Arduino pins labeled A0, A1, A2, etc.
   However, these pins are actually configured using \"normal\" pin numbers in the pin mode message, and when
   those pins are uses for non-analog functions. The analog mapping query provides the information about which pins
   (as used with Firmata's pin mode message) correspond to the analog channels"
  [board]
  (send-message board [m/SYSEX_START m/ANALOG_MAPPING_QUERY m/SYSEX_END]))

(defn query-pin-state
  "Queries the pin state of a given pin (0-127) on the board"
  [board pin]
  (assert (pin? pin) "must supply a valid pin value 0-127")
  (send-message board [m/SYSEX_START m/PIN_STATE_QUERY pin m/SYSEX_END]))

(defn set-pin-mode
  "Sets the mode of a pin (0 to 127), one of: :input, :output, :analog, :pwm, :servo, :i2c"
  [board pin mode]
  (assert (pin? pin) "must supply a valid pin value 0-127")
  (assert (mode mode-values) (str "must supply a valid mode: " (clojure.string/join ", " m/modes)))
  (send-message board [m/SET_PIN_IO_MODE pin (mode mode-values)]))

(defn- enable-reporting
  [board report-type address enabled?]
  (send-message board [(pin-command report-type address) (if enabled? 1 0)]))

(defn enable-analog-in-reporting
  "Enables 'analog in' reporting of a given pin (0-15)."
  [board pin enabled?]
  (assert (pin? pin 16) "must supply a valid pin value 0-15")
  (enable-reporting board m/REPORT_ANALOG_PIN pin enabled?))

(defn enable-digital-port-reporting
  "Enables digital port reporting on a given pin (0-15)."
  [board pin enabled?]
  (assert (pin? pin 16) "must supply a valid pin value 0-15")
  (enable-reporting board m/REPORT_DIGITAL_PORT (port-of pin) enabled?))

(defn set-digital
  "Writes the digital value (max of 2 bytes) to the given pin (0-15)."
  [board pin value]
  (assert (pin? pin 16) "must supply a valid pin value 0-15")

  (let [board-state (:board-state board)
        port (port-of pin)
        pin-value (bit-shift-left 1 (bit-and pin 0x07))
        current-port (get-in @board-state [:digital-out port])
        next-port (if (= :high (high-low-value value))
                    (bit-or current-port pin-value)
                    (bit-and current-port (bit-not pin-value)))]
    (swap! board-state assoc-in [:digital-out port] next-port)
    (send-message board [(pin-command m/DIGITAL_IO_MESSAGE port) (lsb next-port) (msb next-port)])))

(defn set-analog
  "Writes the analog value (max of 2 bytes) to the given pin (0-127)."
  [board pin value]
  (assert (pin? pin) "must supply a valid pin value 0-127")
  (send-message board
       (if (> pin 15)
         [m/SYSEX_START m/EXTENDED_ANALOG pin (lsb value) (msb value) m/SYSEX_END]
         [(pin-command m/ANALOG_IO_MESSAGE pin) (lsb value) (msb value)])))

(defn set-sampling-interval
  "The sampling interval sets how often analog data and i2c data is reported to the client.
   The system default value is 19 milliseconds."
  [board interval]
  (send-message board [m/SYSEX_START m/SAMPLING_INTERVAL (lsb interval) (msb interval) m/SYSEX_END]))

(defn event-channel
  "Returns a channel which provides all of the events that have been returned from the board."
  [board]
  (let [ec ((:create-channel board))]
    (a/tap (:mult-ch board) ec)
    ec))

(defn release-event-channel
  "Releases the channel"
  [board channel]
  (a/untap (:mult-ch board) channel))

(defn event-publisher
  "Returns a publisher which provides events by [:type :pin]"
  [board]
  (:publisher board))

(defn- complete-board 
  "Completes board setup, after all the events are recieved"
  [board-state port create-channel read-ch]
  (let [write-ch (chan 1)
        mult-ch (a/mult read-ch)
        pub-ch (create-channel)
        publisher (a/pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch pub-ch)

    (go (loop []
          (when-let [data (<! write-ch)]
            (spi/write port data)
            (recur))))

    (->Board port board-state read-ch write-ch mult-ch pub-ch publisher create-channel)))

(defn open-board
  "Opens a connection to a board over a given FirmataStream.
  The buffer size for the events may be set with the option :event-buffer size
  (default value 1024)."
  [stream #+cljs on-ready
    & {:keys [event-buffer-size from-raw-digital warmup-time]
       :or {event-buffer-size 1024 from-raw-digital to-keyword warmup-time 5000}}]
  (assert from-raw-digital ":from-raw-digital may not be nil")
  (let [board-state (atom {:digital-out (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))
                           :digital-in  (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))})

        create-channel #(chan (a/sliding-buffer event-buffer-size))
        read-ch (create-channel)

        port (spi/open! stream)
        result-ch (chan 1)]

    (spi/listen port (firmata-handler {:state board-state 
                                      :channel read-ch 
                                      :from-raw-digital from-raw-digital}))

    ; Need to pull these values before wiring up the remaining channels, otherwise, they get lost
    (take-with-timeout! read-ch {:type :protocol-version :version "Unknown"} warmup-time
      (fn [version-event] 
        (swap! board-state assoc :board-version (:version version-event))

        (take-with-timeout! read-ch  {:name "Unknown" :version "Unknown"} warmup-time
          (fn [firmware-event]
            (swap! board-state assoc :board-firmware (dissoc firmware-event :type))

            (a/put! result-ch  
              (complete-board board-state port create-channel read-ch))))))

    #+clj (<!! result-ch)
    #+cljs (a/take! result-ch on-ready)
    ))

(defn open-serial-board
  "Opens a connection to a board at a given port name.
  The baud rate may be set with the option :baud-rate (default value 57600).
  The buffer size for the events may be set with the option :event-buffer size
  (default value 1024)."
  [port-name #+cljs on-ready
   & {:keys [baud-rate event-buffer-size from-raw-digital]
      :or {baud-rate 57600 event-buffer-size 1024 from-raw-digital to-keyword}}]
    #+clj
    (open-board (st/create-serial-stream port-name baud-rate) :event-buffer-size event-buffer-size :from-raw-digital from-raw-digital)
    #+cljs 
    (st/create-serial-stream port-name baud-rate 
      #(open-board % on-ready :event-buffer-size event-buffer-size :from-raw-digital from-raw-digital)))

(defn open-network-board
  "Opens a connection to a board at a host and port.
  The buffer size for the events may be set with the option :event-buffer size
  (default value 1024)."
  [host port #+cljs on-ready
   & {:keys [event-buffer-size from-raw-digital]
      :or {event-buffer-size 1024 from-raw-digital to-keyword}}]
    #+clj  (open-board (st/create-socket-client-stream host port) 
                :warmup-time 0
                :event-buffer-size event-buffer-size
                :from-raw-digital from-raw-digital)
    #+cljs (st/create-socket-client-stream host port 
              #(open-board % on-ready
                  :warmup-time 0
                  :event-buffer-size event-buffer-size
                  :from-raw-digital from-raw-digital)))
