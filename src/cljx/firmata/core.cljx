(ns firmata.core
  (:require [firmata.stream :as st]
            [firmata.sysex :as sysex]
            [firmata.util :as util :refer [lsb msb]]

            #+clj 
            [clojure.core.async :as a :refer [go chan >! <! <!!]]

            #+cljs
            [cljs.core.async    :as a :refer [chan >! <!]])
  #+cljs
  (:require-macros [cljs.core.async.macros :refer [go]]))

; Message Types

(def ^{:private true} ANALOG_IO_MESSAGE   0xE0)
(def ^{:private true} DIGITAL_IO_MESSAGE  0x90)
(def ^{:private true} REPORT_ANALOG_PIN   0xC0)
(def ^{:private true} REPORT_DIGITAL_PORT 0xD0)

(def ^{:private true} SET_PIN_IO_MODE     0xF4)
(def ^{:private true} PROTOCOL_VERSION    0xF9)
(def ^{:private true} SYSTEM_RESET        0xFF)

; Pin Modes
(def ^{:private true} mode-values (zipmap sysex/modes (range 0 (count sysex/modes))))

(def ^{:private true} MAX-PORTS 16)

(defprotocol Firmata
  (close! [board] "Closes the connection to the board.")

  (reset-board! [board] "Sends the reset signal to the board")

  ; Various queries
  (query-firmware [board] "Query the firmware of the board.")

  (query-capabilities [board] "Query the capabilities of the board.")

  (query-version [board] "Query the firmware version of the board.")

  (query-analog-mappings
   [board]
   "Analog messages are numbered 0 to 15, which traditionally refer to the Arduino pins labeled A0, A1, A2, etc.
   However, these pins are actually configured using \"normal\" pin numbers in the pin mode message, and when
   those pins are uses for non-analog functions. The analog mapping query provides the information about which pins
   (as used with Firmata's pin mode message) correspond to the analog channels")

  (query-pin-state [board pin] "Queries the pin state of a given pin (0-127) on the board")

  (set-pin-mode
   [board pin mode]
   "Sets the mode of a pin (0 to 127), one of: :input, :output, :analog, :pwm, :servo, :i2c")

  (enable-analog-in-reporting
   [board pin enabled?]
   "Enables 'analog in' reporting of a given pin (0-15).")

  (enable-digital-port-reporting
   [board pin enabled?]
   "Enables digital port reporting on a given pin (0-15).")

  (set-digital
   [board pin value]
   "Writes the digital value (max of 2 bytes) to the given pin (0-15).")

  (set-analog
   [board pin value]
   "Writes the analog value (max of 2 bytes) to the given pin (0-127).")

  (set-sampling-interval
   [board interval]
   "The sampling interval sets how often analog data and i2c data is reported to the client.
   The system default value is 19 milliseconds.")

  (send-message
   [board args]
   "Sends an arbitrary message to the board.  Useful for I2C message,
   which have user-defined data")

  (event-channel
   [board]
   "Returns a channel which provides all of the events that have been returned from the board.")

  (release-event-channel
   [board channel]
   "Releases the channel")

  (event-publisher
   [board]
   "Returns a publisher which provides events by [:type :pin]")

  (version
   [board]
   "Returns the currently known version of the board")

  (firmware
   [board]
   "Returns the currently known firmware information"))


(defn- read-version
  [in]
  (str (st/read! in) "." (st/read! in)))

(defn- read-analog
  [message in]
  (let [pin (- message ANALOG_IO_MESSAGE)
        value (util/bytes-to-int (st/read! in) (st/read! in))]
    {:type :analog-msg
     :pin pin
     :value value}))

(defn- read-digital
  [board message in]
  (let [port (- message DIGITAL_IO_MESSAGE)
        previous-port (get-in @(:state board)[:digital-in port])
        updated-port (util/bytes-to-int (st/read! in) (st/read! in))
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
  (let [message (st/read! in)]
    (cond
     (= PROTOCOL_VERSION message) (let [version (read-version in)]
                                    {:type :protocol-version, :version version})
     (= sysex/SYSEX_START message) (sysex/read-sysex-event in)
     (<= 0x90 message 0x9F) (read-digital board message in)
     (<= 0xE0 message 0xEF) (read-analog message in)

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

(defn- enable-reporting
  [board report-type address enabled?]
  (send-message board [(pin-command report-type address) (if enabled? 1 0)]))

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

(defrecord ^:private Board [port board-state read-ch write-ch mult-ch pub-ch publisher create-channel]
  Firmata

  (close!
   [this]
   (a/close! write-ch)
   (a/close! read-ch)
   (st/close! port)
   nil)

  (reset-board!
    [this]
    (send-message this SYSTEM_RESET))

  (version
   [this]
   (:board-version @board-state ))

  (firmware
   [this]
   (:board-firmware @board-state))

  (query-firmware
   [this]
   (send-message this [sysex/SYSEX_START sysex/REPORT_FIRMWARE sysex/SYSEX_END]))

  (query-capabilities
   [this]
   (send-message this [sysex/SYSEX_START sysex/CAPABILITY_QUERY sysex/SYSEX_END]))

  (query-version
   [this]
   (send-message this PROTOCOL_VERSION))

  (query-analog-mappings
   [this]
   (send-message this [sysex/SYSEX_START sysex/ANALOG_MAPPING_QUERY sysex/SYSEX_END]))

  (query-pin-state
   [this pin]
   (assert (pin? pin) "must supply a valid pin value 0-127")
   (send-message this [sysex/SYSEX_START sysex/PIN_STATE_QUERY pin sysex/SYSEX_END]))

  (set-pin-mode
   [this pin mode]
   (assert (pin? pin) "must supply a valid pin value 0-127")
   (assert (mode mode-values) (str "must supply a valid mode: " (clojure.string/join ", " sysex/modes)))
   (send-message this [SET_PIN_IO_MODE pin (mode mode-values)]))

  (enable-analog-in-reporting
   [this pin enabled?]
   (assert (pin? pin 16) "must supply a valid pin value 0-15")
   (enable-reporting this REPORT_ANALOG_PIN pin enabled?))

  (enable-digital-port-reporting
   [this pin enabled?]
   (assert (pin? pin 16) "must supply a valid pin value 0-15")
   (enable-reporting this REPORT_DIGITAL_PORT (port-of pin) enabled?))

  (set-digital
   [this pin value]
   (assert (pin? pin 16) "must supply a valid pin value 0-15")

   (let [port (port-of pin)
         pin-value (bit-shift-left 1 (bit-and pin 0x07))
         current-port (get-in @board-state [:digital-out port])
         next-port (if (= :high (high-low-value value))
                     (bit-or current-port pin-value)
                     (bit-and current-port (bit-not pin-value)))]
     (swap! board-state assoc-in [:digital-out port] next-port)
     (send-message this [(pin-command DIGITAL_IO_MESSAGE port) (lsb next-port) (msb next-port)])))

  (set-analog
   [this pin value]
   (assert (pin? pin) "must supply a valid pin value 0-127")
   (send-message this
        (if (> pin 15)
          [sysex/SYSEX_START sysex/EXTENDED_ANALOG pin (lsb value) (msb value) sysex/SYSEX_END]
          [(pin-command ANALOG_IO_MESSAGE pin) (lsb value) (msb value)])))

  (set-sampling-interval
   [this interval]
   (send-message this [sysex/SYSEX_START sysex/SAMPLING_INTERVAL (lsb interval) (msb interval) sysex/SYSEX_END]))

  (send-message
   [this data]
   (a/put! write-ch data)
   this)

  (event-channel
   [this]
   (let [ec (create-channel)]
     (a/tap mult-ch ec)
     ec))

  (release-event-channel
   [this channel]
   (a/untap mult-ch channel))

  (event-publisher
   [this]
   publisher))

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
            (st/write port data)
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

        port (st/open! stream)
        result-ch (chan 1)]

    (st/listen port (firmata-handler {:state board-state 
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

    ; This can be replaced with 
    ; (take! result-ch callback)
    ; for clojurescript
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
  (open-board (st/create-serial-stream port-name baud-rate) 
              #+cljs on-ready
              :event-buffer-size event-buffer-size
              :from-raw-digital from-raw-digital))

(defn open-network-board
  "Opens a connection to a board at a host and port.
  The buffer size for the events may be set with the option :event-buffer size
  (default value 1024)."
  [host port #+cljs on-ready
   & {:keys [event-buffer-size from-raw-digital]
      :or {event-buffer-size 1024 from-raw-digital to-keyword}}]
    (open-board (st/create-socket-client-stream host port) 
                #+cljs on-ready
                :warmup-time 0
                :event-buffer-size event-buffer-size
                :from-raw-digital from-raw-digital))
