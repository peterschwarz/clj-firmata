(defproject clj-firmata "2.1.1-SNAPSHOT"
  :description "A Standard Firmata (http://firmata.org/) client."
  :url "https://github.com/peterschwarz/clj-firmata"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :jar-exclusions [#"\.cljx"]

  :source-paths ["src/clj" "src/cljx"]

  :test-paths ["target/test-classes"]

  :resource-paths ["src/cljs"]

  :jvm-opts ["-Xmx384m"]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2411"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [clj-serial "2.0.2"]]

  :scm {:name "git"
        :url "https://github.com/peterschwarz/clj-firmata"}

  :profiles {:dev {:plugins [[com.keminglabs/cljx "0.5.0"]
                             [lein-cloverage "1.0.2"]
                             [lein-cljsbuild "1.0.4"]
                             [lein-cljfmt "0.1.7"]
                             [com.cemerick/clojurescript.test "0.3.3"]
                             [lein-npm "0.5.0"]]
                   :aliases {"cleantest" ["do" "clean," "cljx" "once," "test,"
                                          "npm" "install," "cljsbuild" "test"]
                             "cljstest" ["do" "cljx" "once," "cljsbuild" "test"]}}}

  :prep-tasks [["cljx" "once"]]

  :node-dependencies [[serialport "1.4.9"]]
  :npm-root "target"

  :cljx {:builds [{:source-paths ["src/cljx"]
                 :output-path "target/classes"
                 :rules :clj}

                {:source-paths ["src/cljx"]
                 :output-path "target/classes"
                 :rules :cljs}

                {:source-paths ["test/cljx"]
                 :output-path "target/test-classes"
                 :rules :clj}

                {:source-paths ["test/cljx"]
                 :output-path "target/test-classes"
                 :rules :cljs}]}

  :cljsbuild {:builds [{:id "test"
                        :source-paths ["src/cljs" "test/cljs" "target/classes" "target/test-classes"]
                        :compiler {:output-to   "target/testable.js"
                                   :output-dir  "target/test-js"
                                   :target :nodejs
                                   :optimizations :simple
                                   :hashbang false}}]
              :test-commands {"unit-tests" ["node" :node-runner
                                            "target/testable.js"]}})
