(ns firmata.core
  (:require [clojure.core.async :as a]
            [serial.core :as serial]))

; Message Types

(def ^{:private true} ANALOG_IO_MESSAGE   0xE0)
(def ^{:private true} DIGITAL_IO_MESSAGE  0x90)
(def ^{:private true} REPORT_ANALOG_PIN   0xC0)
(def ^{:private true} REPORT_DIGITAL_PORT 0xD0)

(def ^{:private true} SYSEX_START         0xF0)
(def ^{:private true} SET_PIN_IO_MODE     0xF4)
(def ^{:private true} SYSEX_END           0xF7)
(def ^{:private true} PROTOCOL_VERSION    0xF9)
(def ^{:private true} SYSEX_RESET         0xFF)

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

(def HIGH 1)
(def LOW 0)

(defrecord Board [serial channel state])

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

(defmulti ^{:private true} read-sysex-event
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

(defn open-board
  "Opens a board on at a given port name.
  The baud rate may be set with the option :baud-rate (default value 57600).
  The buffer size for the events may be set with the option :event-buffer size
  (default value 1024)."
  [port-name & {:keys [baud-rate event-buffer-size]
                 :or {baud-rate 57600 event-buffer-size 1024}}]
  (let [port (serial/open port-name :baud-rate baud-rate)
        ch (a/chan (a/sliding-buffer event-buffer-size))
        board (Board. port ch
                      (atom {:digital-out (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))
                             :digital-in  (zipmap (range 0 MAX-PORTS) (take MAX-PORTS (repeat 0)))}))]
    (serial/listen port (firmata-handler board) false)


    board))

(defn close!
  "Closes the connection to the board."
  [board]
  (serial/close (:serial board))
  (a/close! (:channel board)))

(defn query-firmware
  "Query the firmware of the board"
  [board]
  (serial/write (:serial board) [SYSEX_START REPORT_FIRMWARE SYSEX_END])
  board)

(defn query-capabilities
  "Query the capabilities of the board"
  [board]
  (serial/write (:serial board) [SYSEX_START CAPABILITY_QUERY SYSEX_END])
  board)

(defn query-version
  "Query the firmware version of the board"
  [board]
  (serial/write (:serial board) PROTOCOL_VERSION)
  board)

(defn query-analog-mappings
  "Analog messages are numbered 0 to 15, which traditionally refer to the Arduino pins labeled A0, A1, A2, etc.
  However, these pins are actually configured using \"normal\" pin numbers in the pin mode message, and when
  those pins are uses for non-analog functions. The analog mapping query provides the information about which pins
  (as used with Firmata's pin mode message) correspond to the analog channels"
  [board]
  (serial/write (:serial board) [SYSEX_START ANALOG_MAPPING_QUERY SYSEX_END])
  board)

(defn- pin?
  ([pin] (pin? pin 128))
  ([pin pin-count]
  (and (number? pin) (>= pin 0) (< pin pin-count))))

(defn query-pin-state
  "Queries the pin state of a given pin (0-127) on the board"
  [board pin]
  {:pre [(pin? pin)]}
  (serial/write (:serial board) [SYSEX_START PIN_STATE_QUERY pin SYSEX_END])
  board)

(defn set-pin-mode
  "Sets the mode of a pin (0 to 127), one of: :input, :output, :analog, :pwm, :servo"
  [board pin mode]
  {:pre [(pin? pin) (mode mode-values)]}
  (serial/write (:serial board) [SET_PIN_IO_MODE pin (mode mode-values)])
  board)

(defn- port-of
  "Returns the port of the given pin"
  [pin]
  (bit-and (bit-shift-right pin 3) 0x0F))

(defn- pin-command
  [command pin]
  (bit-and 0xff (bit-or command pin)))

(defn- enable-reporting
  [board report-type address enabled?]
  (serial/write (:serial board) [(pin-command report-type address) (if enabled? 1 0)])
  board)

(defn enable-analog-in-reporting
  "Enables 'analog in' reporting of a given pin (0-15)."
  [board pin enabled?]
  {:pre [(pin? pin 16)]}
  (enable-reporting board REPORT_ANALOG_PIN pin enabled?)
  board)

(defn enable-digital-port-reporting
  "Enables digital port reporting on a given pin (0-15)."
  [board pin enabled?]
  {:pre [(pin? pin 16)]}
  (enable-reporting board REPORT_DIGITAL_PORT (port-of pin) enabled?)
  board)

(defn set-digital
  "Writes the digital value (max of 2 bytes) to the given pin (0-15)."
  [board pin value]
  {:pre [ (pin? pin 16) (or (= :high value) (= :low value))]}
  (let [port (port-of pin)
        pin-value (bit-shift-left 1 (bit-and pin 0x07))
        current-port (get-in @(:state board)[:digital-out port])
        next-port (if (= :high value)
                    (bit-or current-port pin-value)
                    (bit-and current-port (bit-not pin-value)))]
    (swap! (:state board) assoc-in [:digital-out port] next-port)
  (serial/write (:serial board) [(pin-command DIGITAL_IO_MESSAGE port) (lsb next-port) (msb next-port)]))
  board)

(defn set-analog
  "Writes the analog value (max of 2 bytes) to the given pin (0-15)."
  ; todo: pins > 15
  [board pin value]
  {:pre [(pin? pin)]}
  (serial/write (:serial board)
                (if (> pin 15)
                  [SYSEX_START EXTENDED_ANALOG pin (lsb value) (msb value) SYSEX_END]
                  [(pin-command ANALOG_IO_MESSAGE pin) (lsb value) (msb value)]))
  board)

(defn set-sampling-interval
  "The sampling interval sets how often analog data and i2c data is reported to the client.
  The default value is 19 milliseconds."
  ([board] (set-sampling-interval board 19))
  ([board interval]
  (serial/write (:serial board) [SYSEX_START SAMPLING_INTERVAL (lsb interval) (msb interval) SYSEX_END])
   board))

(defn send-i2c-request
  "Sends a I2C read/write request to the board.  "
  [board slave-address mode & data]
  {:pre [(pin? slave-address) (mode i2c-mode-values)]}
  (let [port (:serial board)]
    (serial/write port [SYSEX_START I2C_REQUEST (lsb slave-address) (bit-shift-left (mode i2c-mode-values) 2)])
    (when data
      (serial/write port (reduce #(conj %1 (lsb %2) (msb %2)) [] data)))
    (serial/write port SYSEX_END))
  board)

(defn send-i2c-config
  [board delay]
  (serial/write (:serial board) [SYSEX_START I2C_CONFIG (lsb delay) (msb delay) SYSEX_END] )
  board)

