;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.conformance-test
  "Runs every conformance case against psosf.api on the JVM."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [psosf.api :as api]
            [psosf.conformance :as conformance]
            [psosf.json :as json]))

(def root "conformance")

(defn- load-case [name]
  (json/read-str (slurp (io/file root "cases" name "case.json"))))

(defn- run-case [{:strs [name input options]}]
  (if-let [zip (get input "zip")]
    (api/classify-file (str (io/file root "cases" name zip)) options)
    (api/classify (get input "entries") options)))

(deftest conformance-cases
  (doseq [name (get (json/read-str (slurp (io/file root "cases.json"))) "cases")]
    (let [c (load-case name)]
      (testing name
        (is (empty? (conformance/mismatches (get c "expect") (run-case c))) name)
        (when-let [want (get-in c ["round-trip" "entries"])]
          (is (= want (api/write (get (api/read (get-in c ["input" "entries"])) "archive")))
              (str name ": round trip")))))))
