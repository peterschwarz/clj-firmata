# clj-firmata

[![Build Status](https://travis-ci.org/peterschwarz/clj-firmata.png?branch=master)](https://travis-ci.org/peterschwarz/clj-firmata)

**clj-firmata** provides access to [Standard Firmata](http://firmata.org/) commands via clojure.

## Usage

Add the following to your `project.clj`

	[clj-firmata 1.1.0]

### Connect to a Board

Connecting to a board is a simple as

    (def board (open-board "cu.usbmodemfa141"))

replacing `cu.usbmodemfa141` with the name of the appropriate serial port.

### Communicating with the board.

Performing simple queries on the board will result in events placed onto the board's `core.async` event channel.

For example, calling will result in an event of type `:firmware-report` to be placed on the channel.  I.e. the following would be true:

    (let [ch    (event-channel board)
          _     (query-firmware board)
          event (<!! ch)]
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

The Firmata protocol provides several ways of receiving events from the board.  The first is via an event channel:

	(let [ch (event-channel board)]
	  ; ...
	  ; take events from the channel
	  ; ...
	  ; Then, when you're done, you should clean up:
	  (release-event-channel board ch))

The channels have the same buffer size as the board is configured with on `open-board`.

The protocol also provides a `core.async` publisher, which publishes events based on `[:event-type :pin]`.  This can be used in the standard fashion:

	(let [sub-ch (chan)]
	  (sub (event-publisher board) [:digital-msg 3] sub-ch)
	  (go (loop
	        (when-let [event (<! sub-ch)]
	          ; ... do some stuff
	          (recur)))))

To enable digital pin reporting:

    (-> board
      (set-pin-mode 3 :input)
      (enable-digital-port-reporting 3 true))

This will result in the following events on the channel:

     (let [ch    (event-channel board)
           event (<!! ch)]
        (is (= :digital-msg (:type event)))
        (is (= 3 (:pin event)))
        (is (= :high (:value event)))

For convenience, the `firmata.receiver` namspace provides the function `on-digital-event`, which may be used to filter events with the `:digital-msg` type and to a specific pin.  For example:

    (def receiver (on-digital-event board 3
      #(if (= :high (:value %)) "Pressed" "Released")))

This receiver can be stopped like so:

    (stop-receiver receiver)

Similarly for analog in reporting (on `A0` in this example):

    (enable-analog-in-reporting board 0 true)

will result in the following events on the channel:

     (let [ch    (event-channel board)
           event (<!! ch)]
          (is (= :analog-msg (:type event)))
          (is (= 0 (:pin event)))
          (is (= 1000 (:value event)))

Like `on-digital-event`, there is an `on-analog-event` which will provide the events to a particular analog pin.


### Close the connection to a Board

Board connections should be closed when complete:

    (close! board)

Any channels will be closed as well.

## License

Copyright © 2014 Peter Schwarz

Distributed under the Eclipse Public License, the same as Clojure.
