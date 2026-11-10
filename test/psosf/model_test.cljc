;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.model-test
  "Runs on the JVM and under ClojureScript."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [psosf.core :as core]
            [psosf.model :as m]))

(def accounts
  (-> (m/sheet "Accounts")
      (m/add-column {:header "id" :type "text" :annotation ["Account identifier."]})
      (m/add-column {:header "amount" :type "number" :annotation ["pebblestream:not-blank" "Balance in US dollars."]})
      (m/add-column {:header "negative" :type "boolean" :annotation ["pebblestream:fail"]})
      (m/add-row ["1" "435" (m/formula "=B2<0")])
      (m/add-row {"id" "2" "amount" "-400" "negative" (m/formula "=B3<0")})))

(def ledger
  (-> (m/archive "Account Balances" {:namespace "com.example.ledger"
                                     :license {:name "Apache License 2.0" :spdx "Apache-2.0"
                                               :url "https://www.apache.org/licenses/LICENSE-2.0"}})
      (m/add-sheet accounts)))

(deftest build-then-classify
  (let [entries (core/write-entries ledger)
        result  (core/classify entries {:allowed-functions #{"SUM"}})]
    (is (= :acceptable (:level result)) (pr-str (:errors result) (:acceptance-errors result)))
    (is (= #{"manifest.json" "Accounts.csv"} (set (keys entries))))
    (testing "what was built is what is read back"
      (is (= (:sheets ledger) (:sheets (:archive result))))
      (is (= (:manifest ledger) (:manifest (:archive result)))))))

(deftest traversal
  (is (= ["Accounts"] (m/sheet-names ledger)))
  (is (= ["id" "amount" "negative"] (m/headers accounts)))
  (is (= 1 (m/column-index accounts "amount")))
  (is (= "number" (:type (m/find-column accounts "amount"))))
  (is (= 2 (m/row-count accounts)))
  (is (= (m/formula "=B3<0") (m/cell accounts 1 "negative")))
  (is (= [{"id" "1" "amount" "435" "negative" "=B2<0"}
          {"id" "2" "amount" "-400" "negative" "=B3<0"}]
         (m/row-values accounts)))
  (is (= [{:column "amount" :text "pebblestream:not-blank"} {:column "negative" :text "pebblestream:fail"}]
         (m/directives accounts)))
  (is (= ["Account identifier." "Balance in US dollars."] (map :text (m/comments accounts))))
  (is (= [{:row 0 :column "negative" :text "=B2<0"} {:row 1 :column "negative" :text "=B3<0"}]
         (m/formulas accounts)))
  (is (= "com.example.ledger" (m/manifest-get ledger "namespace"))))

(deftest editing
  (let [a (-> ledger
              (m/update-sheet "Accounts" m/add-column {:header "type" :index 1 :annotation ["pebblestream:allowed-values(\"individual\", \"corporate\")"]})
              (m/update-sheet "Accounts" m/set-cell 0 "type" "individual")
              (m/update-sheet "Accounts" m/add-annotation-line "id" "pebblestream:unique")
              (m/add-sheet (-> (m/sheet "Totals") (m/add-column {:header "total" :type "number"})))
              (m/rename-sheet "Totals" "Account Totals")
              (m/assoc-manifest "description" "Ledger checks."))
        s (m/find-sheet a "Accounts")]
    (is (= ["id" "type" "amount" "negative"] (m/headers s)))
    (is (= ["1" "individual" "435" "=B2<0"] (map :text (first (:rows s)))))
    (is (= "" (:text (m/cell s 1 "type"))))
    (is (= ["Accounts" "Account Totals"] (get-in a [:manifest "sheets"])))
    (is (some #{{:column "id" :text "pebblestream:unique"}} (m/directives s)))
    (is (= :valid (:level (core/classify (core/write-entries a)))))
    (is (= ["id" "amount" "negative"] (m/headers (m/remove-column s "type"))))
    (is (= 1 (m/row-count (m/remove-row s 0))))
    (is (= ["Accounts"] (m/sheet-names (m/remove-sheet a "Account Totals"))))
    (is (thrown? #?(:clj Exception :cljs :default) (m/add-sheet a (m/sheet "Accounts"))))
    (is (thrown? #?(:clj Exception :cljs :default) (m/assoc-manifest a "sheets" [])))
    (is (thrown? #?(:clj Exception :cljs :default) (m/formula "B2<0")))))

(deftest escaping-is-automatic
  (testing "text that would trigger a spreadsheet is escaped on write and restored on read"
    (let [a (m/add-sheet (m/archive "Escapes")
                         (-> (m/sheet "Notes")
                             (m/add-column {:header "note"})
                             (m/add-row ["=not a formula"])
                             (m/add-row ["@SUM(A1)"])
                             (m/add-row ["-400"])))
          entries (core/write-entries a)
          back    (m/find-sheet (:archive (core/read-entries entries)) "Notes")]
      (is (re-find #"\n'=not a formula\n'@SUM\(A1\)\n-400\n$" (get entries "Notes.csv")))
      (is (= ["=not a formula" "@SUM(A1)" "-400"] (map #(get % "note") (m/row-values back))))
      (is (empty? (m/formulas back))))))
