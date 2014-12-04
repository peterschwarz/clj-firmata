# clj-firmata

[![Build Status](https://travis-ci.org/peterschwarz/clj-firmata.png?branch=master)](https://travis-ci.org/peterschwarz/clj-firmata)

**clj-firmata** provides access to [Standard Firmata](http://firmata.org/) commands via Clojure[script].

## Usage

Add the following to your `project.clj`

```clojure
[clj-firmata "2.0.1"]
```

For Clojurescript usage, see [here](doc/clojurescript.md)

### Connect to a Board

Connecting to a board is a simple as

```clojure
(use 'firmata.core)
(def board (open-serial-board "cu.usbmodemfa141"))
```

replacing `cu.usbmodemfa141` with the name of the appropriate serial port.

An valid serial port of a connected arduino may be detected by using `firmata.util/detect-arduino-port.`  Currently, this only works on Mac OS X.

### Communicating with the board.

Performing simple queries on the board will result in events placed onto the board's `core.async` event channel.

For example, calling will result in an event of type `:firmware-report` to be placed on the channel.  I.e. the following would be true:

```clojure
(let [ch    (event-channel board)
      _     (query-firmware board)
      event (<!! ch)]
  (is (= :firmware-report (:type event)))
  (is (= "2.3" (:version event)))
  (is (= "Firmware Name" (:name event))))
```

#### Setting Pin Values

Setting a digital pin value to HIGH (1)

```clojure
(set-digital board 13 :high)
```

and likewise to LOW (0)

```clojure
(set-digital board 13 :low)
```

For analog values a pin must be in `:pwm` mode:

```clojure
(set-pin-mode board 11 :pwm)
(set-analog board 11 255)
```

The above will set the brightness of an LED on pin 11 to maximum brightness

#### Receiving Information

The Firmata protocol provides several ways of receiving events from the board.  The first is via an event channel:

```clojure
(let [ch (event-channel board)]
  ; ...
  ; take events from the channel
  ; ...
  ; Then, when you're done, you should clean up:
  (release-event-channel board ch))
```

The channels have the same buffer size as the board is configured with on `open-board`.

The protocol also provides a `core.async` publisher, which publishes events based on `[:event-type :pin]`.  This can be used in the standard fashion:

```clojure
(let [sub-ch (chan)]
  (sub (event-publisher board) [:digital-msg 3] sub-ch)
  (go (loop
        (when-let [event (<! sub-ch)]
          ; ... do some stuff
          (recur)))))
```

To enable digital pin reporting:

```clojure
(-> board
  (set-pin-mode 3 :input)
  (enable-digital-port-reporting 3 true))
```

This will result in the following events on the channel:

```clojure
(let [ch    (event-channel board)
      event (<!! ch)]
   (is (= :digital-msg (:type event)))
   (is (= 3 (:pin event)))
   (is (= :high (:value event)))
```

By default, the pin value is returned as a key word, either `:high` or `:low`. This may be changed by using the `:from-raw-digital` option when opening the board.  For example:

```clojure
(def board (open-serial-board "cu.usbmodemfa141" :from-raw-digital identity))
```

With this board instance, any read or report of a digital pin's HIGH/LOW state will be `1` or `0`.

For convenience, the `firmata.receiver` namspace provides the function `on-digital-event`, which may be used to filter events with the `:digital-msg` type and to a specific pin.  For example:

```clojure
(def receiver (on-digital-event board 3
  #(if (= :high (:value %)) "Pressed" "Released")))
```

This receiver can be stopped like so:

```clojure
(stop-receiver receiver)
```

Similarly for analog in reporting (on `A0` in this example):

```clojure
(enable-analog-in-reporting board 0 true)
```

will result in the following events on the channel:

```clojure
(let [ch    (event-channel board)
     event (<!! ch)]
    (is (= :analog-msg (:type event)))
    (is (= 0 (:pin event)))
    (is (= 1000 (:value event)))
```

Like `on-digital-event`, there is an `on-analog-event` which will provide the events to a particular analog pin.


### Close the connection to a Board

Board connections should be closed when complete:

```clojure
(close! board)
```

Any channels will be closed as well.

## License

Copyright © 2014 Peter Schwarz

Distributed under the Eclipse Public License, the same as Clojure.
