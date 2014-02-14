(ns firmata.core
  (:require [clojure.core.async :refer [chan go <! >!]]
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
(def ^{:private true} modes [:input :output :analog :pwm :servo])
(def ^{:private true} mode-values {:input 0, :output 1 :analog 2 :pwm 3 :servo 4})

(def HIGH 1)
(def LOW 0)

(defrecord Board [port channel])

(defn- to-number
  "Converts a sequence of bytes into an (long) number."
  [bytes]
  (long (BigInteger. (byte-array (count bytes) bytes)))
  )

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
        mode (.read in)
        value (to-number (consume-sysex in [] #(conj %1 (byte %2))))]
    {:type :pin-state
     :pin pin
     :mode mode
     :value value})
  )

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

(defmulti ^{:private true} read-event
  (fn [in] (.read in)))

(defmethod read-event PROTOCOL_VERSION
  [in]
  (let [version (read-version in)]
    {:type :protocol-version, :version version}))

(defmethod read-event SYSEX_START
  [in]
  (read-sysex-event in))

(defmethod read-event :default
  [in]
  {:type :unknown-message
   ; TODO: Replace value in the message
   ; :value ?
   })

(defn- firmata-handler
  [board]
  (fn [in]
    (let [event (read-event in)]
      (go (>! (:channel board) event)))))


(defn open-board
  "Opens a board on at a given port name"
  [port-name]
  ; TODO add more parameters (baud rate, etc)
  (let [port (serial/open port-name 57600)
        board (Board. port (chan))]
    (serial/listen port (firmata-handler board) false)
    board))

(defn close
  [board]
  (serial/close (:port board)))

(defn query-firmware
  "Query the firmware of the board"
  [board]
  (serial/write (:port board) [SYSEX_START REPORT_FIRMWARE SYSEX_END]))

(defn query-capabilities
  "Query the capabilities of the board"
  [board]
  (serial/write (:port board) [SYSEX_START CAPABILITY_QUERY SYSEX_END]))

(defn query-version
  "Query the firmware version of the board"
  [board]
  (serial/write (:port board) PROTOCOL_VERSION))

(defn query-analog-mappings
  "Analog messages are numbered 0 to 15, which traditionally refer to the Arduino pins labeled A0, A1, A2, etc.
  However, these pins are actually configured using \"normal\" pin numbers in the pin mode message, and when
  those pins are uses for non-analog functions. The analog mapping query provides the information about which pins
  (as used with Firmata's pin mode message) correspond to the analog channels"
  [board]
  (serial/write (:port board) [SYSEX_START ANALOG_MAPPING_QUERY SYSEX_END]))

(defn- pin?
  ([pin] (pin? pin 128))
  ([pin pin-count]
  (and (number? pin) (>= pin 0) (< pin pin-count))))

(defn query-pin-state
  "Queries the pin state of a given pin (0-127) on the board"
  [board pin]
  {:pre [(pin? pin)]}
  (serial/write (:port board) [SYSEX_START PIN_STATE_QUERY pin SYSEX_END]))

(defn set-pin-mode
  "Sets the mode of a pin (0 to 127), one of: :input, :output, :analog, :pwm, :servo"
  [board pin mode]
  {:pre [(pin? pin) (mode mode-values)]}
  (serial/write (:port board) [SET_PIN_IO_MODE pin (mode mode-values)]))

(defn- pin-command
  [command pin]
  (bit-and 0x000000ff (bit-or (.byteValue command) (.byteValue pin))))

(defn- write-pin-command
  [board report-type pin & more]
  {:pre [(pin? pin 16)]}
  (serial/write (:port board) (apply vector (conj more (pin-command report-type pin)))))

(defn enable-analog-in-reporting
  "Enables 'analog in' reporting of a given pin (0-15)."
  [board pin enabled?]
  (write-pin-command board REPORT_ANALOG_PIN pin (if enabled? 1 0)))

(defn enable-digital-port-reporting
  "Enables digital port reporting on a given pin (0-15)."
  [board pin enabled?]
  (write-pin-command board REPORT_DIGITAL_PORT pin (if enabled? 1 0)))

(defn- lsb "Least significant byte"
  [b]
  (bit-and (.byteValue b) 0x7F))

(defn- msb "Most significant byte (of a 16-bit value)"
  [b]
  (bit-and (bit-shift-right (.byteValue b) 7) 0x7F))

(defn set-digital
  "Writes the digital value (max of 2 bytes) to the given pin (0-15)."
  [board pin value]
  (write-pin-command board DIGITAL_IO_MESSAGE pin (lsb value) (msb value)))

(defn set-analog
  "Writes the analog value (max of 2 bytes to the given pin (0-15)."
  [board pin value]
  (write-pin-command board ANALOG_IO_MESSAGE pin (lsb value) (msb value)))
