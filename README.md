# clj-firmata

[![Build Status](https://travis-ci.org/peterschwarz/clj-firmata.png?branch=master)](https://travis-ci.org/peterschwarz/clj-firmata)

**clj-firmata** provides access to [Standard Firmata](http://firmata.org/) commands via clojure.

## Usage

Add the following to your `project.clj`

	[clj-firmata 0.1.0-SNAPSHOT]

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

### Close the connection to a Board

Board connections should be closed when complete:

    (close board)


## License

Copyright © 2014 Peter Schwarz

Distributed under the Eclipse Public License, the same as Clojure.
