(ns firmata.core
  (:require [firmata.messages :as m]
            [firmata.stream :as st]
            [firmata.stream.spi :as spi :refer [read!]]
            [firmata.sysex :refer [read-sysex-event]]
            [firmata.util :as util :refer [lsb msb]]
            #?(:clj
               [clojure.core.async :as a :refer [go go-loop chan >! <! <!!]])
            #?(:cljs
               [cljs.core.async    :as a :refer [chan >! <!]])
            #?(:cljs [clojure.string]))
  #?(:cljs
     (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

; Pin Modes
(def ^:private mode-values (zipmap m/modes (range 0 (count m/modes))))

(def ^:private MAX-PORTS 16)

(defrecord ^:private Board [stream ^clojure.lang.Atom board-state read-ch write-ch mult-ch pub-ch publisher create-channel])

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
        previous-port (get-in @(:state board) [:digital-in port])
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
  (try
    (let [message (read! in)]
      (cond
        (= m/PROTOCOL_VERSION message) (let [version (read-version in)]
                                         {:type :protocol-version, :version version})
        (= m/SYSEX_START message) (read-sysex-event in)
        (m/is-digital? message) (read-digital board message in)
        (m/is-analog? message)  (read-analog message in)

        :else {:type :unknown-msg
               :value message}))
    (catch #?(:clj Exception :cljs js/Error) e
           e)))

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
    (let [[v source-chan] (a/alts! [ch (a/timeout millis)])]
      (f (or v default)))))

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

(defn- do-format
  [raw-value high low]
  (if (= 1 raw-value) high low))

(defmulti format-raw-digital
  "Formats the raw values received from digital reads of pin state,
  or digital events.

  The type specified is a keyword."
  (fn [type _] type))

(defmethod format-raw-digital :keyword
  [_ raw-value]
  (do-format raw-value :high :low))

(defmethod format-raw-digital :boolean
  [_ raw-value]
  (= 1 raw-value))

(defmethod format-raw-digital :symbol
  [_ raw-value]
  (do-format raw-value 'high 'low))

(defmethod format-raw-digital :char
  [_ raw-value]
  (do-format raw-value \1 \0))

(defmethod format-raw-digital :default
  [_ raw-value]
  raw-value)

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
  (try
    (spi/close! (:stream board))
    nil
    (catch #?(:clj Exception :cljs js/Error) e
           e)))

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

(defn send-string
  "Sends a string to the board. String length is limited to half the buffer size - 3
  (for Arduino this limits strings to 30 chars)."
  [board string]
  (send-message board
                (flatten [m/SYSEX_START m/STRING_DATA (map byte string) m/SYSEX_END])))

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

(defn- safe-write
  "Writes to the given stream, catching any exceptions.
  If one is thrown while writing, it is returned."
  [stream data]
  (try
    (spi/write stream data)
    nil
    (catch #?(:clj Exception :cljs js/Error) e
           e)))

(defn- run-write-loop [stream write-ch error-ch]
  "Run the write loop, which pulls data from the write channel, and
  writes it, in sequence, to the stream."
  (go-loop []
    (when-let [data (<! write-ch)]
      (if-let [exception (safe-write stream data)]
        (do
          (>! error-ch exception)
          (a/close! write-ch))
        (recur)))))

(defn- complete-board
  "Completes board setup, after all the events are received"
  [board-state port create-channel read-ch]
  (let [write-ch (chan 1)
        mult-ch (a/mult read-ch)
        pub-ch (create-channel)
        publisher (a/pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch pub-ch)

    (run-write-loop port write-ch read-ch)

    (->Board port board-state read-ch write-ch mult-ch pub-ch publisher create-channel)))

(defn- open-board-chan
  [stream
   & {:keys [event-buffer-size digital-result-format from-raw-digital warmup-time reset-on-connect?]
      :or {event-buffer-size 1024 digital-result-format :keyword warmup-time 5000 reset-on-connect? false}}]
  (let [board-state (atom {:digital-out (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))
                           :digital-in  (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))})
        formatter (or from-raw-digital
                      (partial format-raw-digital digital-result-format))
        create-channel #(chan (a/sliding-buffer event-buffer-size))
        read-ch (create-channel)

        port (spi/open! stream)
        result-ch (chan 1)]

    (go
      (when reset-on-connect?
        (spi/write port m/SYSTEM_RESET)
        (a/timeout 100)) ; wait for the reset...

      (spi/listen port (firmata-handler {:state board-state
                                         :channel read-ch
                                         :from-raw-digital formatter}))

      ; Need to pull these values before wiring up the remaining channels, otherwise, they get lost
      (take-with-timeout! read-ch {:type :protocol-version :version "Unknown"} warmup-time
                          (fn [version-event]
                            (swap! board-state assoc :board-version (:version version-event))

                            (take-with-timeout! read-ch  {:name "Unknown" :version "Unknown"} warmup-time
                                                (fn [firmware-event]
                                                  (swap! board-state assoc :board-firmware (dissoc firmware-event :type))

                                                  (a/put! result-ch
                                                          (complete-board board-state port create-channel read-ch)))))))

    result-ch))

(defn open-board
  "Opens a connection to a board over a given FirmataStream.

  Options:

  * `:event-buffer-size` - the number of messages buffered on read (defaults to 1024)
  * `:digital-result-format` - the format for digital read values (defaults to `:keyword` i.e. `:high`/`:low`)
  * `:from-raw-digital` - a function for converting the raw 1/0 value of digital pin to a useful value (overrides `:digital-result-format`)
  * `:warmup-time` - the time to wait for the board to 'settle' (defaults to 5 sec)
  * `:reset-on-connect?` - indicates whether or not a reset message should be set to the board during warmup (defaults to false)"
  [stream #?(:cljs on-ready) & args]
  (let [result-ch (apply open-board-chan stream args)]
    #?(:clj (<!! result-ch)
       :cljs (a/take! result-ch on-ready))))

(defn open-serial-board
  "Opens a connection to a board at a given port name.

  Arguments

  * `port-name-or-auto-detect` - the name of the serial port or :auto-detect

  Options:

  * `:baud-rate` - the serial baud rate (defaults to 576000)
  * `:event-buffer-size` - the number of messages buffered on read (defaults to 1024)
  * `:digital-result-format` - the format for digital read values (defaults to `:keyword` i.e. `:high`/`:low`)
  * `:from-raw-digital` - a function for converting the raw 1/0 value of digital pin to a useful value (overrides `:digital-result-format`)
  * `:warmup-time` - the time to wait for the board to 'settle' (defaults to 5 sec)
  * `:reset-on-connect?` - indicates whether or not a reset message should be set to the board during warmup (defaults to false)"
  [port-name-or-auto-detect #?(:cljs on-ready)
   & {:keys [baud-rate event-buffer-size digital-result-format from-raw-digital reset-on-connect?]
      :or {baud-rate 57600 event-buffer-size 1024 digital-result-format :keyword reset-on-connect? false}}]
  (assert port-name-or-auto-detect "port-name-or-auto-detect may not be nil")
  #?(:clj
     (let [port-fn (if (= port-name-or-auto-detect :auto-detect)
                     util/detect-arduino-port
                     (fn [] port-name-or-auto-detect))]
       (open-board (st/create-serial-stream (port-fn) baud-rate)
                   :event-buffer-size event-buffer-size
                   :digital-result-format digital-result-format
                   :from-raw-digital from-raw-digital
                   :reset-on-connect? reset-on-connect?)))
  #?(:cljs
     (let [port-fn (if (= port-name-or-auto-detect :auto-detect)
                     util/detect-arduino-port
                     (fn [callback] (callback nil port-name-or-auto-detect)))]
       (port-fn (fn [err port-name]
                  (when (not err)
                    (st/create-serial-stream port-name baud-rate
                                             #(open-board % on-ready
                                                          :event-buffer-size event-buffer-size
                                                          :from-raw-digital from-raw-digital
                                                          :reset-on-connect? reset-on-connect?))))))))

(defn open-network-board
  "Opens a connection to a board at a host and port.

  Options:

  * `:event-buffer-size` - the number of messages buffered on read (defaults to 1024)
  * `:digital-result-format` - the format for digital read values (defaults to `:keyword` i.e. `:high`/`:low`)
  * `:from-raw-digital` - a function for converting the raw 1/0 value of digital pin to a useful value (overrides `:digital-result-format`)"
  [host port #?(:cljs on-ready)
   & {:keys [event-buffer-size digital-result-format from-raw-digital]
      :or {event-buffer-size 1024 digital-result-format :keyword}}]
  #?(:clj  (open-board (st/create-socket-client-stream host port)
                       :warmup-time 0
                       :event-buffer-size event-buffer-size
                       :digital-result-format digital-result-format
                       :from-raw-digital from-raw-digital))
  #?(:cljs (st/create-socket-client-stream host port
                                           #(open-board % on-ready
                                                        :warmup-time 0
                                                        :event-buffer-size event-buffer-size
                                                        :from-raw-digital from-raw-digital))))

(defn create-network-board-server
  "Creates a board server on a port.  Returns a platform-specfic server object.

  Options:

  * `:host` - the server host (defaults to '0.0.0.0')
  * `:event-buffer-size` - the number of messages buffered on read (defaults to 1024)
  * `:digital-result-format` - the format for digital read values (defaults to `:keyword` i.e. `:high`/`:low`)
  * `:from-raw-digital` - a function for converting the raw 1/0 value of digital pin to a useful value (overrides `:digital-result-format`)
  * `:warmup-time` - the time to wait for the board to 'settle' (defaults to 5 sec)
  * `:reset-on-connect?` - indicates whether or not a reset message should be set to the board during warmup (defaults to false)"
  [port on-connected
   & {:keys [host event-buffer-size from-raw-digital digital-result-format]
      :or   {host "0.0.0.0" event-buffer-size 1024 digital-result-format :keyword}}]
  (st/create-socket-server-stream host port (fn [client]
                                              (a/take! (open-board-chan client :event-buffer-size event-buffer-size
                                                                        :from-raw-digital from-raw-digital)
                                                       on-connected))))
