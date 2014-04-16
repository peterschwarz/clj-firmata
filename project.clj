(defproject clj-firmata "1.1.0"
  :description "clj-firmata provides access to Standard Firmata (http://firmata.org/) commands via clojure"
  :url "https://github.com/peterschwarz/clj-firmata"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [clj-serial "2.0.1"]]
  :scm {:name "git"
        :url "https://github.com/peterschwarz/clj-firmata"}
  :plugins [[lein-cloverage "1.0.2"]])
