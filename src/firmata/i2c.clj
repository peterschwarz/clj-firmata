(ns firmata.i2c
    (:require [firmata.core :refer :all]
              [firmata.sysex :refer [SYSEX_START SYSEX_END]]
              [firmata.util :refer :all]))

(def ^{:private true} I2C_REQUEST             0x76 ); I2C request messages from a host to an I/O board
(def ^{:private true} I2C_REPLY               0x77 ); I2C reply messages from an I/O board to a host
(def ^{:private true} I2C_CONFIG              0x78 ); Configure special I2C settings such as power pins and delay times

; I2C Modes
(def ^{:private true} i2c-modes [:write :read-once :read-continuously :stop-reading])
(def ^{:private true} i2c-mode-values {:write 2r00, :read-once 2r01
                                       :read-continuously 2r10 :stop-reading 2r11})


(defmethod read-sysex-event I2C_REPLY
  [in]
  (let [slave-address (bytes-to-int (.read in) (.read in))
        register (bytes-to-int (.read in) (.read in))
        data (read-two-byte-data in)]
    {:type :i2c-reply
     :slave-address slave-address
     :register register
     :data data}))

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
