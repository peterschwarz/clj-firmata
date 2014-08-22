(defproject clj-firmata "2.0.0-SNAPSHOT"
  :description "clj-firmata provides access to Standard Firmata (http://firmata.org/) commands via clojure"
  :url "https://github.com/peterschwarz/clj-firmata"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [clj-serial "2.0.2"]]
  :scm {:name "git"
        :url "https://github.com/peterschwarz/clj-firmata"}
  :plugins [[lein-cloverage "1.0.2"]])
