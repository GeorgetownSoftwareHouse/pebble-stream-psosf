;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [pebblestream.directives.schema :as s]))

(deftest registry-consistency
  (is (= {} (s/registry-errors)) "every entry matches the directive-entry schema")
  (is (empty? (s/undefined-references)) "requires and excludes name defined directives")
  (is (empty? (s/excludes-errors)) "every exclusion is listed on both directives"))

(defn- valid? [d] (nil? (s/explain d)))

(deftest documented-signatures
  (testing "valid"
    (doseq [d [{:directive :from :syntax :paren :args ["Accounts"]}
               {:directive :join :syntax :paren :args ["Orders"]}
               {:directive :join :syntax :paren :args ["Taxable" "From" "Account From" "To" "Account To"]}
               {:directive :filter :syntax :paren :args ["Active"]}
               {:directive :filter :syntax :paren :args ["Amount" ">0" "Amount" {:operator "<=" :column "Balance"}]}
               {:directive :filter :syntax :paren :args ["Amount" {:ref "Inputs!B2"}]}
               {:directive :sort :syntax :paren :args ["Last" "First" {:column "Date" :order :desc}]}
               {:directive :sorted :syntax :bare :args []}
               {:directive :sorted :syntax :colon :args ["desc" 2]}
               {:directive :hint :syntax :colon :args ["balance" "Use the closing balance."]}
               {:directive :stop :syntax :bare :args []}
               {:directive :stop :syntax :paren :args [1000]}
               {:directive :unique :syntax :bare :args []}
               {:directive :unique :syntax :paren :args []}
               {:directive :coalesce :syntax :bare :args []}
               {:directive :id :syntax :colon :args ["account key"]}
               {:directive :workflow-iterate :syntax :paren :args [10]}]]
      (is (valid? d) (pr-str d))))
  (testing "invalid"
    (doseq [d [{:directive :filter :syntax :paren :args ["Amount" ">0" "Color"]}
               {:directive :stop :syntax :paren :args [1 2]}
               {:directive :sorted :syntax :colon :args ["up"]}
               {:directive :from :syntax :bare :args []}
               {:directive :hint :syntax :bare :args ["x"]}
               {:directive :from :syntax :paren :args ["bad/name"]}]]
      (is (not (valid? d)) (pr-str d)))))

(deftest worksheet-rules
  (is (= [{:column "total" :written-by [:sum :mult]}]
         (s/group-write-conflicts [{:directive :sum :args ["amount" "total"]} {:directive :mult :args ["rate" "total"]}])))
  (is (= [{:column "total" :written-by :sum}]
         (s/undeclared-targets ["type"] [{:directive :sum :args ["amount" "total"]}]))))

(deftest status-and-order
  (is (s/deprecated-directive? {:directive :group-sum}))
  (is (not (s/deprecated-directive? {:directive :header-map})))
  (is (s/deprecated-syntax? {:directive :hint :syntax :colon}))
  (is (= 13 (s/execution-step :prune)))
  (is (= 7 (s/execution-step :sum))))
