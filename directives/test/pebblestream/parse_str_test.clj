;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.parse-str-test
  (:require [clojure.test :refer [deftest is testing]]
            [pebblestream.parse-str :as p]))

(def cases
  [["\"abc\""          "abc"]
   ["\"\""             ""]
   ["  \"abc\"\u00A0"  "abc"]
   ["\"12\\\" pipe\""  "12\" pipe"]      ; backslash style
   ["\"12\"\" pipe\""  "12\" pipe"]      ; Excel style
   ["\"\"\"\""         "\""]
   ["\"a\"\"\"\"b\""   "a\"\"b"]
   ["\"\\\\\""         "\\"]
   ["\"C:\\\\data\""   "C:\\data"]
   ["\"\\\"\"\"\""     "\"\""]
   ["\"a\\\"\"\"b\""   "a\"\"b"]
   ["\"abc"            nil]
   ["abc\""            nil]
   ["\""               nil]
   ["\"\"\""           nil]               ; a lone quote inside
   ["\"a\"b\""         nil]
   ["\"abc\\\""        nil]])             ; escaped closing quote

(deftest parse-str-cases
  (doseq [[in want] cases]
    (is (= want (p/parse-str in)) (pr-str in))))

(deftest ambiguous-backslash-warnings
  (doseq [[in value indexes] [["\"C:\\data\\in\""  "C:datain"  [3 8]]
                              ["\"C:\\\\data\""    "C:\\data"  []]
                              ["\"12\\\" pipe\""   "12\" pipe" []]
                              ["\"12\"\" pipe\""   "12\" pipe" []]
                              ["\"\\n\""           "n"         [1]]]]
    (let [{v :value ws :warnings} (p/parse-str* in)]
      (is (= value v) (pr-str in))
      (is (= indexes (mapv :index ws)) (pr-str in))
      (is (every? #(= :ambiguous-backslash (:code %)) ws)))))

(def ^:private alphabet [\a \b \space \" \\ \u00A0 \x])
(defn- rand-text [] (apply str (repeatedly (rand-int 9) #(rand-nth alphabet))))

(deftest quote-str-round-trip
  (testing "quote-str output parses back exactly and never warns"
    (dotimes [_ 20000]
      (let [s (rand-text)
            {v :value ws :warnings} (p/parse-str* (p/quote-str s))]
        (is (= s v))
        (is (empty? ws))))))
