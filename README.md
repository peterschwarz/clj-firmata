# clj-firmata

[![Build Status](https://travis-ci.org/peterschwarz/clj-firmata.png?branch=master)](https://travis-ci.org/peterschwarz/clj-firmata)

**clj-firmata** provides access to [Standard Firmata](http://firmata.org/) commands via clojure.

## Usage

Add the following to your `project.clj`

	[clj-firmata 0.1.0]

### Connect to a Board

Connecting to a board is a simple as

    (def board (open-board "cu.usbmodemfa141"))

replacing `cu.usbmodemfa141` with the name of the appropriate serial port.

### Communicating with the board.

Performing simple queries on the board will result in events placed onto the board's `core.async` event channel.

For example, calling

    (query-firmware board)

will result in an event of type `:firmware-report` to be placed on the channel.  I.e. the following would be true:

    (let [event (<!! (:channel board))]
            (is (= :firmware-report (:type event)))
            (is (= "2.3" (:version event)))
            (is (= "Firmware Name" (:name event))))

#### Setting Pin Values

Setting a digital pin value to HIGH (1)

    (set-digital board 13 :high)
    
and likewise to LOW (0)

    (set-digital board 13 :low)
    
For analog values a pin must be in `:pwm` mode:

    (set-pin-mode board 11 :pwm)
    (set-analog board 11 255)
    
The above will set the brightness of an LED on pin 11 to maximum brightness
    
#### Receiving Information

To enable digital pin reporting:

	(enable-digital-port-reporting board 3 true)
	
This will result in the following events on the channel:

     (let [event (<!! (:channel board))]
            (is (= :digital-msg (:type event)))
            (is (= 3 (:pin event)))
            (is (= :high (:value event)))

Similarly for analog in reporting (on `A0` in this example):

	(enable-analog-in-reporting board 0 true)
	
will result in the following events on the channel:

     (let [event (<!! (:channel board))]
          (is (= :analog-msg (:type event)))
          (is (= 0 (:pin event)))
          (is (= 1000 (:value event)))

### Close the connection to a Board

Board connections should be closed when complete:

    (close board)
    
The board's channel is closed as well.


## License

Copyright © 2014 Peter Schwarz

Distributed under the Eclipse Public License, the same as Clojure.
