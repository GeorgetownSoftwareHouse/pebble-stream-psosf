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

(deftest once-per-worksheet
  (is (= [{:directive :join :count 2}]
         (map #(select-keys % [:directive :count])
              (s/once-per-worksheet-errors [{:directive :from} {:directive :join} {:directive :join}]))))
  (is (empty? (s/once-per-worksheet-errors [{:directive :join} {:directive :outer-join}]))
      "join and outer-join are each limited separately")
  (is (empty? (s/once-per-worksheet-errors [{:directive :filter} {:directive :filter} {:directive :fail} {:directive :fail}]))
      "filter and fail may repeat")
  (is (true? (get-in s/all-directives [:outer-join :once-per-worksheet])))
  (is (nil? (get-in s/all-directives [:filter :once-per-worksheet]))))

(deftest from-family-and-repeats
  (testing "the transformative directives that work with from appear once"
    (doseq [k [:from :cartesian-product :join :outer-join :group :sort :header-map :paths :apportion :relieve]]
      (is (true? (get-in s/all-directives [k :once-per-worksheet])) (name k))))
  (testing "directives that write a column may repeat with different targets"
    (doseq [k [:paths-operation :sum :yield :npv :pivot]]
      (is (nil? (get-in s/all-directives [k :once-per-worksheet])) (name k)))
    (is (empty? (s/write-conflicts [{:directive :paths-operation :args ["product" "a"]}
                                    {:directive :paths-operation :args ["sum" "b"]}])))
    (is (= [{:column "a" :written-by [:paths-operation :paths-operation]}]
           (s/write-conflicts [{:directive :paths-operation :args ["product" "a"]}
                               {:directive :paths-operation :args ["sum" "a"]}])))
    (is (= [{:column "irr" :written-by [:yield :npv]}]
           (s/write-conflicts [{:directive :yield :args ["a" "b" "c" "d" "irr"]}
                               {:directive :npv :args ["a" "b" "c" "d" "irr"]}])))))

(deftest once-per-spreadsheet
  (doseq [k [:template-name :expand :generate-rows :workflow-filter :match-rows]]
    (is (true? (get-in s/all-directives [k :once-per-worksheet])) (name k)))
  (doseq [k [:workflow-all :workflow-each :workflow-group :workflow-iterate]]
    (is (true? (get-in s/all-directives [k :once-per-spreadsheet])) (name k)))
  (testing "workflow-group may annotate several columns of one worksheet"
    (is (empty? (s/once-per-spreadsheet-errors
                 {"Orders" [{:directive :from} {:directive :workflow-group} {:directive :workflow-group}]}))))
  (testing "but not appear on two worksheets"
    (is (= [{:directive :workflow-group :sheets ["Orders" "Lines"]}]
           (map #(select-keys % [:directive :sheets])
                (s/once-per-spreadsheet-errors {"Orders" [{:directive :workflow-group}]
                                                "Lines"  [{:directive :workflow-group}]})))))
  (testing "workflow-each appears once in the whole spreadsheet"
    (is (= [:workflow-each]
           (map :directive (s/once-per-spreadsheet-errors {"Orders" [{:directive :workflow-each} {:directive :workflow-each}]}))))
    (is (= [:workflow-iterate]
           (map :directive (s/once-per-spreadsheet-errors {"A" [{:directive :workflow-iterate}] "B" [{:directive :workflow-iterate}]}))))))

(deftest pivot-is-exclusive
  (is (= 7 (s/execution-step :pivot)) "pivot runs within the group step")
  (doseq [k [:sum :ratio :mult :equal :min :max :average :balance :yield :npv :expand :coalesce]]
    (is (contains? (get-in s/all-directives [:pivot :excludes]) k) (str "pivot excludes " (name k)))
    (is (contains? (get-in s/all-directives [k :excludes]) :pivot) (str (name k) " excludes pivot")))
  (is (not (contains? (get-in s/all-directives [:pivot :excludes]) :group))))

(deftest pivot-category-columns
  (testing "pivots may share a category column when they share its value column"
    (is (empty? (s/pivot-conflicts [{:directive :pivot :args ["account" "Q1" "amount" "text"]}
                                    {:directive :pivot :args ["account" "Q2" "amount" "text"]}]))))
  (testing "a category column may not pull from different value columns"
    (is (= [{:category-column "account" :value-columns ["amount" "units"]}]
           (map #(dissoc % :message)
                (s/pivot-conflicts [{:directive :pivot :args ["account" "Q1" "amount" "text"]}
                                    {:directive :pivot :args ["account" "Q2" "units" "text"]}])))))
  (testing "different category columns are independent"
    (is (empty? (s/pivot-conflicts [{:directive :pivot :args ["account" "Q1" "amount" "text"]}
                                    {:directive :pivot :args ["fund" "Q1" "units" "text"]}])))))

(deftest status-and-order
  (is (s/deprecated-directive? {:directive :group-sum}))
  (is (not (s/deprecated-directive? {:directive :header-map})))
  (is (s/deprecated-syntax? {:directive :hint :syntax :colon}))
  (is (= 13 (s/execution-step :prune)))
  (is (= 7 (s/execution-step :sum))))
