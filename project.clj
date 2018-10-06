(defproject clj-firmata "2.1.2-SNAPSHOT"
  :description "A Standard Firmata (http://firmata.org/) client."
  :url "https://github.com/peterschwarz/clj-firmata"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/clj" "src/cljc" "test/cljc"]

  :resource-paths ["src/cljs"]

  :jvm-opts ["-Xmx384m"]

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.238"]
                 [org.clojure/core.async "0.4.474"]
                 [clj-serial "2.0.3"]]

  :scm {:name "git"
        :url "https://github.com/peterschwarz/clj-firmata"}

  :profiles {:dev {:plugins [[lein-cloverage "1.0.2"]
                             [lein-cljsbuild "1.1.5"]
                             [com.cemerick/clojurescript.test "0.3.3"]
                             [lein-npm "0.5.0"]
                             [codox "0.8.12"]]

                   :aliases {"cleantest" ["do" "clean," "test,"
                                          "npm" "install," "cljsbuild" "test"]
                             "cljstest" ["do" "cljsbuild" "test"]}}}

  :node-dependencies [[serialport "4.0.7"]]
  :npm-root "target"

  :codox {:defaults {:doc/format :markdown}
          :sources  ["src/clj" "src/cljc"]
          :exclude [firmata.stream.impl]
          :output-dir "target/doc"}

  :cljsbuild {:builds [{:id "test"
                        :source-paths ["src/cljc" "src/cljs" "test/cljc" "test/cljs"]
                        :compiler {:output-to   "target/testable.js"
                                   :output-dir  "target/test-js"
                                   :target :nodejs
                                   :optimizations :simple
                                   :hashbang false}}]
              :test-commands {"unit-tests" ["node" :node-runner
                                            "target/testable.js"]}})
