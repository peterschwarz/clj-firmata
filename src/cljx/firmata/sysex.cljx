(ns firmata.sysex
    (:require [firmata.messages :as m]
              [firmata.util :as util]
              [firmata.stream :refer [read!]]))


(defn consume-sysex
  "Consumes bytes until the end of a SysEx response."
  [in initial accumulator]
  (util/consume-until m/SYSEX_END in initial accumulator))

(defn- read-capabilities
  "Reads the capabilities response from the input stream"
  [in]
  (loop [result {}
         current-value (read! in)
         pin 0]
    (if (= m/SYSEX_END current-value)
      result
      (recur (assoc result pin
               (loop [pin-modes {}
                      pin-mode current-value]
                 (if (= 0x7F pin-mode)
                   pin-modes
                   (recur (assoc pin-modes (get m/modes pin-mode :future-mode) (read! in))
                          (read! in))
                   )))
             (read! in)
             (inc pin)))))

(defn read-two-byte-data
  "Consumes the value of a SysEx message as an list of short values."
  [in]
  (loop [result []
         current-byte (read! in)]
    (if (= m/SYSEX_END current-byte )
      result
      (recur (conj result (util/bytes-to-int current-byte (read! in)))
             (read! in)))))

(defn- read-analog-mappings
  [in]
  (loop [result {}
         current-byte (read! in)
         pin 0]
    (if (= m/SYSEX_END current-byte)
      result
      (recur (if-not (= current-byte 0x7F) (assoc result current-byte pin) result)
             (read! in)
             (inc pin)))))

(defmulti read-sysex-event
  "Reads a sysex message.  

   Returns a map with, at a minimum, the key :type.  This should 
   indicates what sort of sysex message is being received.

   For example, the result of a REPORT_FIRMWARE message is
   
       { :type :firmaware-report
         :version \"2.3\"
         :name \"StandardFirmata\" }"
  (fn [in] (read! in)))

(defmethod read-sysex-event m/REPORT_FIRMWARE
  [in]
  (let [version (str (read! in) "." (read! in))
        name (consume-sysex in "" #(str %1 (char %2)))]
    {:type :firmware-report
     :version version
     :name name}))

(defmethod read-sysex-event m/CAPABILITY_RESPONSE
  [in]
  (let [report (read-capabilities in)]
    {:type :capabilities-report
     :modes report}))

(defmethod read-sysex-event m/PIN_STATE_RESPONSE
  [in]
  (let [pin (read! in)
        mode (get m/modes (read! in) :future-mode)
        value (util/to-number (consume-sysex in '() #(conj %1 (byte %2))))]
    {:type :pin-state
     :pin pin
     :mode mode
     :value value}))

(defmethod read-sysex-event m/ANALOG_MAPPING_RESPONSE
  [in]
  (let [mappings (read-analog-mappings in)]
    {:type :analog-mappings
     :mappings mappings}))

(defmethod read-sysex-event m/STRING_DATA
  [in]
  (let [data (consume-sysex in "" #(str %1 (char %2)))]
    {:type :string-data
     :data data}))

(defmethod read-sysex-event :default
  [in]
  (let [values (consume-sysex in '[] #(conj %1 %2))]
    {:type :unknown-sysex
     :value values}))
