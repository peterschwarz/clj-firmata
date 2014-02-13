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

(defrecord Board [port channel])

(defn- to-hex
  "For debug output"
  [x] (Integer/toHexString x))

(defn- consume-until
  [end-signal in initial accumulator]
  (loop [current-value (.read in)
          result initial]
     (if (= end-signal current-value)
       result
       (recur (.read in)
              (accumulator result current-value)))))

(defn- consume-sysex
  [in initial accumulator]
   (consume-until SYSEX_END in initial accumulator))

(defn- read-capabilities
  [in]

    (loop [result {}
           current-value (.read in)
           pin 0]
      (if (= SYSEX_END current-value)
        result
        (recur (assoc result pin
                 (loop [modes {}
                    pin-mode current-value]
                  (if (= 0x7F pin-mode)
                    modes
                    (recur (assoc modes pin-mode (.read in))
                           (.read in))
                    )))
               (.read in)
               (inc pin))

        )))

(defn- read-version
  [in]
  (str (.read in) "." (.read in)))

(defmulti read-sysex-event
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

(defmethod read-sysex-event :default
  [in]
  (let [values (consume-sysex in '() #(conj %1 %2))]
    {:type :unknown-sysex
     :value values}))

(defmulti read-event
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
  [port-name]
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

(defn query-pin-state
  "Queries the pin state of a given pin (0-127) on the board"
  [board pin]
  {:pre [(number? pin) (>= pin 0) (< pin 128)]}
  (serial/write (:port board) [SYSEX_START PIN_STATE_QUERY pin SYSEX_END]))
