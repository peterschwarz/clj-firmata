# clj-firmata

[![Build Status](https://travis-ci.org/peterschwarz/clj-firmata.png?branch=master)](https://travis-ci.org/peterschwarz/clj-firmata)

**clj-firmata** provides access to [Standard Firmata](http://firmata.org/) commands via Clojure[script].

## Usage

Add the following to your `project.clj`

```clojure
[clj-firmata "2.1.1-SNAPSHOT"]
```

For Clojurescript usage, see [here](doc/clojurescript.md)

### Connect to a Board

Connecting to a board is a simple as

```clojure
(use 'firmata.core)
(def board (open-serial-board "cu.usbmodemfa141"))
```

replacing `cu.usbmodemfa141` with the name or path of the appropriate serial port.

An valid serial port of a connected arduino may be autodetected by passing `:auto-detect`  Currently, this only works on Mac OS X and Linux.

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

By default, the pin value is returned as a key word, either `:high` or `:low`. This may be changed by using the `:digital-result-format ` option when opening the board.  For example:

```clojure
(def board (open-serial-board "cu.usbmodemfa141" :digital-result-format :raw))
```

With this board instance, any read or report of a digital pin's HIGH/LOW state will be `1` or `0`, respectively.  One can use `:keyword`, `:boolean`, `:char`, `:symbol` and `:raw`.  The default is `:keyword`.

One can also write a custom digital formatter by passing a function for the `:from-raw-digital`:

```clojure
(def board (open-serial-board "cu.usbmodemfa141" 
                              :from-raw-digital #(if (= 1 %) :foo :bar)))
```

With this board instance, any read or report of a digital pin's HIGH/LOW state will be `:foo` or `:bar` respectively

For convenience, the `firmata.async` namspace provides the function `digital-event-chan`, which creates a channel with filtered events with the `:digital-msg` type and a specific pin.  For example:

```clojure
(let [ch (digital-event-chan board 3)]
  (go-loop [evt (<! ch)]
    (if (= :high (:value evt)) "Pressed" "Released")))
```

This ch can be closed like any other:

```clojure
; Assuming (require '[clojure.core.async :as a])
(a/close! receiver)
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

Like `digital-event-chan`, there is an `analog-event-chan` which will provide the events to a particular analog pin.

### Exceptions and Error handling

Any exceptions that occur while reading or writing to the board will be forwarded along the event channel. 

### Close the connection to a Board

Board connections should be closed when complete:

```clojure
(close! board)
```

Any channels will be closed as well.

## License

Copyright © 2014 Peter Schwarz

Distributed under the Eclipse Public License, the same as Clojure.
