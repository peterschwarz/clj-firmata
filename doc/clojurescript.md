# Using clj-firmata with Clojurescript

**`clj-firmata`** is supported in Clojurescript on Node.js targets.

## Setup

First, you'll need to install [node-serialport](https://github.com/voodootikigod/node-serialport) in your project:

    > npm install serialport  

Build your application as you would normally.  

## Differences from Clojure

The main difference is in opening a board. Unlike the Clojure version of `open-board` and its siblings, the Clojurescript version takes a callback. 

    (ns my-firmata-app
        (:require [firmata.core :as f]))
    (defn -main []
     (f/open-serial-board "/dev/tty.usbmodemfd131" (fn [board]
        ; do what you like here with the standard clj-firmata api
        ))

