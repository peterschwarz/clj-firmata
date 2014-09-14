(ns firmata.stream.spi)


(defprotocol ByteReader
  "Enables reading on byte at a time."

  (read! [this] "reads a byte, and removes it from the stream"))


(defprotocol FirmataStream
  "A FirmataStream provides methods for creating connections, writing
  values to and listening for events on a Firmata-enabled device."

  (open! [this] "opens the stream")
  (close! [this] "closes the stream")

  (listen [this handler] "listens for data on this stream")
  (write [this data]))
