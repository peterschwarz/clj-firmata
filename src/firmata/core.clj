(ns firmata.core
  (:require [serial.core :as serial]))

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

(defrecord Board [port])

(defn- to-hex
  "For debug output"
  [x] (Integer/toHexString x))

(defn- consume-sysex
  [in initial accumulator]
   (loop [current-value (.read in)
          result initial]
     (if (= SYSEX_END current-value)
       result
       (recur (.read in)
              (accumulator result current-value)))))

(defn- read-version
  [in]
  (str (.read in) "." (.read in)))

(defn- sysex-handler
  [in]
  (let [command (.read in)]
    (cond
     (= command REPORT_FIRMWARE) (do
                                   (println "**Reporting Firmware**")
                                   (println "Firmware version:" (read-version in))
                                   (println (consume-sysex in "" #(str %1 (char %2)))))
          :else (do
                  (println "Unknown SysEx Message:" (to-hex command))
                  (consume-sysex '() #(conj %1 %2))))))


(defn- firmata-handler
  [board]
  (fn [in]
    (let [message (.read in)]
      (cond
       (= PROTOCOL_VERSION message) (do (println "Protocol Version:" (read-version in)))
       (= SYSEX_START message) (sysex-handler in)
       :else (println "Unknown Message:" (to-hex message))))))



(defn connect
  [port-name]
  (let [port (serial/open port-name 57600)
        board (Board. port)]
    (serial/listen port (firmata-handler board) false)
    board))

(defn close
  [board]
  (serial/close (:port board)))

(defn query-firmware
  "Query the firmware of the board"
  [board]
    (serial/write (:port board)
      [SYSEX_START REPORT_FIRMWARE SYSEX_END]))

(defn request-version
  "Query the firmware version of the board"
  [board]
  (serial/write (:port board) PROTOCOL_VERSION))
