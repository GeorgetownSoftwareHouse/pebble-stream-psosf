;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [pebblestream.directives.parse :as p]))

(defn- parsed [line] (dissoc (p/parse-line line) :warnings))

(deftest forms
  (is (= {:directive :unique :syntax :bare :args []} (parsed "pebblestream:unique")))
  (is (= {:directive :unique :syntax :paren :args []} (parsed "pebblestream:unique()")))
  (is (= {:directive :join :syntax :paren :args ["Rates" "id" "rate-id"]}
         (parsed "pebblestream:join(\"Rates\", \"id\", \"rate-id\")")))
  (is (= {:directive :hint :syntax :colon :args ["balance" "closing"]} (parsed "pebblestream:hint:balance:closing")))
  (is (= {:directive :sorted :syntax :colon :args ["desc" 2]} (parsed "pebblestream:sorted:desc:2")))
  (is (= {:directive :div-0 :syntax :bare :args []} (parsed "pebblestream:div/0")))
  (is (= {:directive :from :syntax :paren :args ["Accounts"]} (parsed "  pebblestream:from(Accounts)  "))))

(deftest arguments
  (testing "unquoted names, numbers, keywords"
    (is (= ["FundClasses" "Fund ID" 12 -0.5 :latest]
           (:args (parsed "pebblestream:join(FundClasses, \"Fund ID\", 12, -0.5, :latest)")))))
  (testing "single-quoted sheet names"
    (is (= ["2010's Data"] (:args (parsed "pebblestream:from('2010''s Data')")))))
  (testing "cell references name their worksheet"
    (is (= [{:ref "Inputs!B2"} {:ref "'Rate Table'!$C$4"}]
           (:args (parsed "pebblestream:filter(Inputs!B2, 'Rate Table'!$C$4)"))))
    (is (= ["A1" "B2"] (:args (parsed "pebblestream:group(A1, B2)")))
        "without a worksheet, A1 is a name, not a reference"))
  (testing "sort columns"
    (is (= ["Last" {:column "Date" :order :desc} {:column "Amount" :order :asc}]
           (:args (parsed "pebblestream:sort(\"Last\", \"Date\":desc, Amount:asc)")))))
  (testing "column and cell predicates"
    (is (= ["Amount" {:operator "<=" :column "Balance"} "Amount" {:operator "<" :ref "Inputs!B2"}]
           (:args (parsed "pebblestream:filter(\"Amount\", \"<=\"&\"Balance\", \"Amount\", \"<\"&Inputs!B2)")))))
  (testing "commas and parentheses inside strings"
    (is (= ["a, b" "(c)"] (:args (parsed "pebblestream:allowed-values(\"a, b\", \"(c)\")")))))
  (testing "both escape styles"
    (is (= ["12\" pipe" "12\" pipe"] (:args (parsed "pebblestream:allowed-values(\"12\"\" pipe\", \"12\\\" pipe\")"))))))

(deftest colon-form-separators
  (is (= ["a" "b"] (:args (parsed "pebblestream:allowed-values:a:b"))))
  (is (= ["a,b"] (:args (parsed "pebblestream:allowed-values:a,b"))) "commas do not separate colon-form arguments")
  (is (= ["key" "a, b"] (:args (parsed "pebblestream:hint:key:\"a, b\"")))))

(deftest warnings-and-errors
  (is (= [:ambiguous-backslash] (map :code (:warnings (p/parse-line "pebblestream:hint(\"C:\\data\")")))))
  (is (:error (p/parse-line "pebblestream:join(\"Rates\"")))
  (is (:error (p/parse-line "pebblestream:join(\"Rates)")))
  (is (:error (p/parse-line "pebblestream:")))
  (is (:error (p/parse-line "Totals by account."))))
