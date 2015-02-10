(ns firmata.messages)

; Message Types

(def ANALOG_IO_MESSAGE   0xE0)
(def DIGITAL_IO_MESSAGE  0x90)
(def REPORT_ANALOG_PIN   0xC0)
(def REPORT_DIGITAL_PORT 0xD0)

(def SET_PIN_IO_MODE     0xF4)
(def PROTOCOL_VERSION    0xF9)
(def SYSTEM_RESET        0xFF)

; SysEx messages

(def SYSEX_START         0xF0)
(def SYSEX_END           0xF7)

; SysEx Commands

(def RESERVED_COMMAND        0x00); 2nd SysEx data byte is a chip-specific command (AVR, PIC, TI, etc).
(def ANALOG_MAPPING_QUERY    0x69); ask for mapping of analog to pin numbers
(def ANALOG_MAPPING_RESPONSE 0x6A); reply with mapping info
(def CAPABILITY_QUERY        0x6B); ask for supported modes and resolution of all pins
(def CAPABILITY_RESPONSE     0x6C); reply with supported modes and resolution
(def PIN_STATE_QUERY         0x6D); ask for a pin's current mode and value
(def PIN_STATE_RESPONSE      0x6E); reply with a pin's current mode and value
(def EXTENDED_ANALOG         0x6F); analog write (PWM, Servo, etc) to any pin
(def SERVO_CONFIG            0x70); set max angle, minPulse, maxPulse, freq
(def STRING_DATA             0x71); a string message with 14-bits per char
(def SHIFT_DATA              0x75); shiftOut config/data message (34 bits)
(def REPORT_FIRMWARE         0x79); report name and version of the firmware
(def SAMPLING_INTERVAL       0x7A); sampling interval
(def SYSEX_NON_REALTIME      0x7E); MIDI Reserved for non-realtime messages
(def SYSEX_REALTIME          0x7F); MIDI Reserved for realtime messages

; I2C Messages

(def I2C_REQUEST             0x76); I2C request messages from a host to an I/O board
(def I2C_REPLY               0x77); I2C reply messages from an I/O board to a host
(def I2C_CONFIG              0x78); Configure special I2C settings such as power pins and delay times


; TODO: This seems to be mixing a bit of things from the other
(def  modes [:input :output :analog :pwm :servo :shift :i2c])

(defn is-digital? [message]
  (<= 0x90 message 0x9F))

(defn is-analog? [message]
  (<= 0xE0 message 0xEF))
