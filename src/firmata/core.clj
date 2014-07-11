(ns firmata.core
  (:require [clojure.core.async :as a :refer [go chan >! >!! <! alts!! <!!]]
            [firmata.stream :as st])
  (:import [firmata.stream SerialStream]))

; Message Types

(def ^{:private true} ANALOG_IO_MESSAGE   0xE0)
(def ^{:private true} DIGITAL_IO_MESSAGE  0x90)
(def ^{:private true} REPORT_ANALOG_PIN   0xC0)
(def ^{:private true} REPORT_DIGITAL_PORT 0xD0)

(def ^{:private true} SYSEX_START         0xF0)
(def ^{:private true} SET_PIN_IO_MODE     0xF4)
(def ^{:private true} SYSEX_END           0xF7)
(def ^{:private true} PROTOCOL_VERSION    0xF9)
(def ^{:private true} SYSTEM_RESET        0xFF)

; SysEx Commands

(def ^{:private true} RESERVED_COMMAND        0x00 ); 2nd SysEx data byte is a chip-specific command (AVR, PIC, TI, etc).
(def ^{:private true} ANALOG_MAPPING_QUERY    0x69 ); ask for mapping of analog to pin numbers
(def ^{:private true} ANALOG_MAPPING_RESPONSE 0x6A ); reply with mapping info
(def ^{:private true} CAPABILITY_QUERY        0x6B ); ask for supported modes and resolution of all pins
(def ^{:private true} CAPABILITY_RESPONSE     0x6C ); reply with supported modes and resolution
(def ^{:private true} PIN_STATE_QUERY         0x6D ); ask for a pin's current mode and value
(def ^{:private true} PIN_STATE_RESPONSE      0x6E ); reply with a pin's current mode and value
(def ^{:private true} EXTENDED_ANALOG         0x6F ); analog write (PWM, Servo, etc) to any pin
(def ^{:private true} SERVO_CONFIG            0x70 ); set max angle, minPulse, maxPulse, freq
(def ^{:private true} STRING_DATA             0x71 ); a string message with 14-bits per char
(def ^{:private true} SHIFT_DATA              0x75 ); shiftOut config/data message (34 bits)
(def ^{:private true} I2C_REQUEST             0x76 ); I2C request messages from a host to an I/O board
(def ^{:private true} I2C_REPLY               0x77 ); I2C reply messages from an I/O board to a host
(def ^{:private true} I2C_CONFIG              0x78 ); Configure special I2C settings such as power pins and delay times
(def ^{:private true} REPORT_FIRMWARE         0x79 ); report name and version of the firmware
(def ^{:private true} SAMPLING_INTERVAL       0x7A ); sampling interval
(def ^{:private true} SYSEX_NON_REALTIME      0x7E ); MIDI Reserved for non-realtime messages
(def ^{:private true} SYSEX_REALTIME          0x7F ); MIDI Reserved for realtime messages

; Pin Modes
(def ^{:private true} modes [:input :output :analog :pwm :servo :shift :i2c])
(def ^{:private true} mode-values {:input 0, :output 1 :analog 2 :pwm 3 :servo 4 :shift 5 :i2c 6})

; I2C Modes
(def ^{:private true} i2c-modes [:write :read-once :read-continuously :stop-reading])
(def ^{:private true} i2c-mode-values {:write 2r00, :read-once 2r01
                                       :read-continuously 2r10 :stop-reading 2r11})
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
   "Returns the currently known firmware information")

  )

; Number conversions

(defn- lsb "Least significant byte"
  [x]
  (bit-and x 0x7F))

(defn- msb "Most significant byte (of a 16-bit value)"
  [x]
  (bit-and (bit-shift-right x 7) 0x7F))

(defn- to-number
  "Converts a sequence of bytes into an (long) number."
  [values]
  (reduce #(bit-or (bit-shift-left %1 7) (bit-and %2 0x7f)) 0 values))

(defn- bytes-to-int
  [lsb msb]
  (to-number [msb lsb]))

(defn- consume-until
  "Consumes bytes from the given input stream until the end-signal is reached."
  [end-signal in initial accumulator]
  (loop [current-value (.read in)
         result initial]
    (if (= end-signal current-value)
      result
      (recur (.read in)
             (accumulator result current-value)))))

(defn- consume-sysex
  "Consumes bytes until the end of a SysEx response."
  [in initial accumulator]
  (consume-until SYSEX_END in initial accumulator))

(defn- read-capabilities
  "Reads the capabilities response from the input stream"
  [in]
  (loop [result {}
         current-value (.read in)
         pin 0]
    (if (= SYSEX_END current-value)
      result
      (recur (assoc result pin
               (loop [pin-modes {}
                      pin-mode current-value]
                 (if (= 0x7F pin-mode)
                   pin-modes
                   (recur (assoc pin-modes (get modes pin-mode :future-mode) (.read in))
                          (.read in))
                   )))
             (.read in)
             (inc pin))

      )))

(defn- read-version
  [in]
  (str (.read in) "." (.read in)))

(defmulti read-sysex-event
  "Reads a sysex message.  

   Returns a map with, at a minimum, the key :type.  This should 
   indicates what sort of sysex message is being received.

   For example, the result of a REPORT_FIRMWARE message is
   
       { :type :firmaware-report
         :version \"2.3\"
         :name \"StandardFirmata\" }"
  (fn [in] (.read in)))

(defmethod read-sysex-event REPORT_FIRMWARE
  [in]
  (let [version (read-version in)
        name (consume-sysex in "" #(str %1 (char %2)))]
    {:type :firmware-report
     :version version
     :name name}))

(defmethod read-sysex-event CAPABILITY_RESPONSE
  [in]
  (let [report (read-capabilities in)]
    {:type :capabilities-report
     :modes report}))

(defmethod read-sysex-event PIN_STATE_RESPONSE
  [in]
  (let [pin (.read in)
        mode (get modes (.read in) :future-mode)
        value (to-number (consume-sysex in '() #(conj %1 (byte %2))))]
    {:type :pin-state
     :pin pin
     :mode mode
     :value value})
  )

(defn- read-two-byte-data
  [in]
  (loop [result []
         current-byte (.read in)]
    (if (= SYSEX_END current-byte )
      result
      (recur (conj result (bytes-to-int current-byte (.read in)))
             (.read in)))))

(defmethod read-sysex-event I2C_REPLY
  [in]
  (let [slave-address (bytes-to-int (.read in) (.read in))
        register (bytes-to-int (.read in) (.read in))
        data (read-two-byte-data in)]
    {:type :i2c-reply
     :slave-address slave-address
     :register register
     :data data}))

(defn- read-analog-mappings
  [in]
  (loop [result {}
         current-byte (.read in)
         pin 0]
    (if (= SYSEX_END current-byte)
      result
      (recur (if-not (= current-byte 0x7F) (assoc result current-byte pin) result)
             (.read in)
             (inc pin)))))

(defmethod read-sysex-event ANALOG_MAPPING_RESPONSE
  [in]
  (let [mappings (read-analog-mappings in)]
    {:type :analog-mappings
     :mappings mappings}))

(defmethod read-sysex-event STRING_DATA
  [in]
  (let [data (consume-sysex in "" #(str %1 (char %2)))]
    {:type :string-data
     :data data}))

(defmethod read-sysex-event :default
  [in]
  (let [values (consume-sysex in '[] #(conj %1 %2))]
    {:type :unknown-sysex
     :value values}))

(defn- read-analog
  [message in]
  (let [pin (- message ANALOG_IO_MESSAGE)
        value (bytes-to-int (.read in) (.read in))]
    {:type :analog-msg
     :pin pin
     :value value}))

(defn- read-digital
  [board message in]
  (let [port (- message DIGITAL_IO_MESSAGE)
        previous-port (get-in @(:state board)[:digital-in port])
        updated-port (bytes-to-int (.read in) (.read in))
        pin-change (- updated-port previous-port)
        pin (-> pin-change Math/abs BigInteger/valueOf .getLowestSetBit (+ (* 8 port)))
        value (if (> pin-change 0) :high :low)]
    (swap! (:state board) assoc-in [:digital-in port] updated-port)
    {:type :digital-msg
     :port port
     :pin pin
     :value value}))

(defn- read-event
  [board in]
  (let [message (.read in)]
    (cond
     (= PROTOCOL_VERSION message) (let [version (read-version in)]
                                    {:type :protocol-version, :version version})
     (= SYSEX_START message) (read-sysex-event in)
     (<= 0x90 message 0x9F) (read-digital board message in)
     (<= 0xE0 message 0xEF) (read-analog message in)

     :else {:type :unknown-msg
            :value message
            })))

(defn- firmata-handler
  [board]
  (fn [in]
    (let [event (read-event board in)]
      (a/go (>! (:channel board) event)))))

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

(defn- take-with-timeout
  [ch default]
  (or (first (alts!! [ch (a/timeout 5000)])) default))

(declare create-board)

(defn open-board
  "Opens a connection to a board on at a given port name.
  The baud rate may be set with the option :baud-rate (default value 57600).
  The buffer size for the events may be set with the option :event-buffer size
  (default value 1024)."
  [port-name & {:keys [baud-rate event-buffer-size]
                :or {baud-rate 57600 event-buffer-size 1024}}]
  (create-board (SerialStream. port-name baud-rate) event-buffer-size))

(defn- create-board
  [stream event-buffer-size]
  (let [board-state (atom {:digital-out (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))
                           :digital-in  (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))})

        create-channel #(chan (a/sliding-buffer event-buffer-size))
        read-ch (create-channel)
        write-ch (chan 1)

        ; Open the stream and attach ourselves to it
        port (st/open! stream)
        _ (st/listen port (firmata-handler {:state board-state :channel read-ch}))

        ; Need to pull these values before wiring up the remaining channels, otherwise, they get lost
        _ (swap! board-state assoc :board-version (take-with-timeout read-ch {:type :protocol-version :version "Unknown"}))
        _ (swap! board-state assoc :board-firmware (take-with-timeout read-ch {:type :firmware-report :name "Unknown" :version "Unknown"}))

        ; For sending events to various receivers
        mult-ch (a/mult read-ch)
        pub-ch (create-channel)
        publisher (a/pub pub-ch #(vector (:type %) (:pin %)))]

    (a/tap mult-ch pub-ch)

    (go (loop []
          (when-let [data (<! write-ch)]
            (st/write port data)
            (recur))))

    (reify Firmata
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
       (-> @board-state :board-version :version))

      (firmware
       [this]
        (dissoc (:board-firmware @board-state) :type))

      (query-firmware
       [this]
       (send-message this [SYSEX_START REPORT_FIRMWARE SYSEX_END]))

      (query-capabilities
       [this]
       (send-message this [SYSEX_START CAPABILITY_QUERY SYSEX_END]))

      (query-version
       [this]
       (send-message this PROTOCOL_VERSION))

      (query-analog-mappings
       [this]
       (send-message this [SYSEX_START ANALOG_MAPPING_QUERY SYSEX_END]))

      (query-pin-state
       [this pin]
       (assert (pin? pin) "must supply a valid pin value 0-127")
       (send-message this [SYSEX_START PIN_STATE_QUERY pin SYSEX_END]))

      (set-pin-mode
       [this pin mode]
       (assert (pin? pin) "must supply a valid pin value 0-127")
       (assert (mode mode-values) (str "must supply a valid mode: " (clojure.string/join ", " modes)))
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
       (assert (or (= :high value) (= :low value)) "must supply either :high or :low")

       (let [port (port-of pin)
             pin-value (bit-shift-left 1 (bit-and pin 0x07))
             current-port (get-in @board-state [:digital-out port])
             next-port (if (= :high value)
                         (bit-or current-port pin-value)
                         (bit-and current-port (bit-not pin-value)))]
         (swap! board-state assoc-in [:digital-out port] next-port)
         (send-message this [(pin-command DIGITAL_IO_MESSAGE port) (lsb next-port) (msb next-port)])))

      (set-analog
       [this pin value]
       (assert (pin? pin) "must supply a valid pin value 0-127")
       (send-message this
            (if (> pin 15)
              [SYSEX_START EXTENDED_ANALOG pin (lsb value) (msb value) SYSEX_END]
              [(pin-command ANALOG_IO_MESSAGE pin) (lsb value) (msb value)])))

      (set-sampling-interval
       [this interval]
       (send-message this [SYSEX_START SAMPLING_INTERVAL (lsb interval) (msb interval) SYSEX_END]))

      (send-message
       [this data]
       (>!! write-ch data)
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
       publisher))))

(defn send-i2c-request
 "Sends an I2C read/write request with optional extended data."
 [board slave-address mode & data]
 {:pre [(mode i2c-mode-values)]}
 (send-message board (concat [SYSEX_START I2C_REQUEST (lsb slave-address) (bit-shift-left (mode i2c-mode-values) 2)]
                             (reduce #(conj %1 (lsb %2) (msb %2)) [] data)
                             [SYSEX_END])))

(defn send-i2c-config
  "Sends an I2C config message with a delay and optional user-defined data."
  [board delay & data]
  (let [msg (concat [SYSEX_START I2C_CONFIG (lsb delay) (msb delay)]
                    data
                    [SYSEX_END])]
    (send-message board msg)))
