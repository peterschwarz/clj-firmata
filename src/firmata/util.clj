(ns firmata.util)


(defn to-hex-str
  "For debug output"
  [x] (str "0x" (.toUpperCase(Integer/toHexString x))))
