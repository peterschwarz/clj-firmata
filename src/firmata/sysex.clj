(ns firmata.sysex
    (:require [firmata.util :refer :all]))

; SysEx messages

(def SYSEX_START         0xF0)
(def SYSEX_END           0xF7)

; SysEx Commands

(def RESERVED_COMMAND        0x00 ); 2nd SysEx data byte is a chip-specific command (AVR, PIC, TI, etc).
(def ANALOG_MAPPING_QUERY    0x69 ); ask for mapping of analog to pin numbers
(def ANALOG_MAPPING_RESPONSE 0x6A ); reply with mapping info
(def CAPABILITY_QUERY        0x6B ); ask for supported modes and resolution of all pins
(def CAPABILITY_RESPONSE     0x6C ); reply with supported modes and resolution
(def PIN_STATE_QUERY         0x6D ); ask for a pin's current mode and value
(def PIN_STATE_RESPONSE      0x6E ); reply with a pin's current mode and value
(def EXTENDED_ANALOG         0x6F ); analog write (PWM, Servo, etc) to any pin
(def SERVO_CONFIG            0x70 ); set max angle, minPulse, maxPulse, freq
(def STRING_DATA             0x71 ); a string message with 14-bits per char
(def SHIFT_DATA              0x75 ); shiftOut config/data message (34 bits)
(def REPORT_FIRMWARE         0x79 ); report name and version of the firmware
(def SAMPLING_INTERVAL       0x7A ); sampling interval
(def SYSEX_NON_REALTIME      0x7E ); MIDI Reserved for non-realtime messages
(def SYSEX_REALTIME          0x7F ); MIDI Reserved for realtime messages

; TODO: This seems to be mixing a bit of things from the other
(def  modes [:input :output :analog :pwm :servo :shift :i2c])


(defn consume-sysex
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
             (inc pin)))))

(defn read-two-byte-data
  "Consumes the value of a SysEx message as an list of short values."
  [in]
  (loop [result []
         current-byte (.read in)]
    (if (= SYSEX_END current-byte )
      result
      (recur (conj result (bytes-to-int current-byte (.read in)))
             (.read in)))))

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
  (let [version (str (.read in) "." (.read in))
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
     :value value}))

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
