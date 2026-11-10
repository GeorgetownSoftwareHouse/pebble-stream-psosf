;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.core-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [psosf.accept :as accept]
            [psosf.api :as api]
            [psosf.core :as core]
            [psosf.csv :as csv]
            [psosf.field :as field]
            [psosf.json :as json]))

(def accounts-csv
  (str "Account identifier from the general ledger.,\"pebblestream:not-blank\nCustomer classification: individual or corporate.\",Balance in US dollars.,\"pebblestream:fail\nStops the run when a balance is zero or negative.\"\n"
       "text,text,number,boolean\n"
       ",,,\n"
       "id,type,amount,amount is not positive\n"
       "1,individual,435,psosf:=C2<=0\n"
       "2,corporate,-400,psosf:=C3<=0\n"))

(def manifest-map
  {"description" "Validates ledger accounts." "format" "psosf" "format-version" "1.1"
   "license" {"name" "Apache License 2.0" "spdx" "Apache-2.0" "url" "https://www.apache.org/licenses/LICENSE-2.0"}
   "name" "Account Balances" "namespace" "com.example.ledger" "sheets" ["Accounts"]})

(def entries {"manifest.json" (json/write-canonical manifest-map) "Accounts.csv" accounts-csv})

(def allow #{"SUM" "IF"})

(deftest json-test
  (let [doc {"b" [1 2.5 "x\"y"] "a" {"z" true "y" nil} "é" "ü"}]
    (is (= doc (json/read-str (json/write-canonical doc))))
    (is (= "{\"a\":{\"y\":null,\"z\":true},\"b\":[1,2.5,\"x\\\"y\"],\"é\":\"ü\"}" (json/write-compact doc)))
    (is (= "{\n  \"a\": [\n    1\n  ]\n}\n" (json/write-canonical {"a" [1]})))
    (is (thrown? Exception (json/read-str "{\"a\":1,\"a\":2}")))))

(deftest csv-test
  (let [recs [["a" "b,c" "d\"e" "line1\nline2" ""]]]
    (is (= recs (csv/read-records (csv/write-records recs))))
    (is (= [["a" "b"] ["c" "d"]] (csv/read-records "a,b\r\nc,d")))
    (is (thrown? Exception (csv/read-records "\"open")))))

(deftest field-test
  (testing "writing"
    (is (= "psosf:=C2<=0" (field/write-content {:kind :formula :text "=C2<=0"})))
    (doseq [[text written] [["=total" "'=total"] ["@SUM(A1)" "'@SUM(A1)"] ["+1 555 0100" "'+1 555 0100"]
                            ["psosf:=x" "'psosf:=x"] ["'quoted'" "''quoted'"] ["-400" "-400"] ["plain" "plain"]]]
      (is (= written (field/write-text text)) text)
      (is (= {:kind :text :text text} (field/read-field written {:cell? true})) text)))
  (testing "reading"
    (is (= :unescaped-trigger (:error (field/read-field "@SUM(A1)" {:cell? true}))))
    (is (= "Write psosf:=@SUM(A1) if it is a formula, or '@SUM(A1) if it is text."
           (:fix (field/read-field "@SUM(A1)" {:cell? true}))))
    (is (= :formula-outside-cells (:error (field/read-field "psosf:=1" {:cell? false}))))
    (is (= :bad-formula-prefix (:error (field/read-field "psosf:A1" {:cell? true}))))
    (is (= {:kind :formula :text "=A1"} (field/read-field "=A1" {:cell? true :legacy? true})))))

(deftest classify-levels
  (testing "valid but no allow-list configured"
    (let [r (core/classify entries)]
      (is (= :valid (:level r)))
      (is (= [:allow-list-not-configured] (map :code (:acceptance-errors r))))))
  (testing "acceptable with an allow-list"
    (is (= :acceptable (:level (core/classify entries {:allowed-functions allow})))))
  (testing "a function outside the allow-list"
    (let [r (core/classify (assoc entries "Accounts.csv" (string/replace accounts-csv "psosf:=C2<=0" "psosf:=WEBSERVICE(\"\"https://x\"\")"))
                           {:allowed-functions allow})]
      (is (= :valid (:level r)))
      (is (= [:function-not-allowed] (map :code (:acceptance-errors r))))))
  (testing "external references and DDE"
    (is (= #{:external-reference}
           (set (map :code (:acceptance-errors (core/classify (assoc entries "Accounts.csv" (string/replace accounts-csv "psosf:=C2<=0" "psosf:=[Other.xlsx]Sheet1!A1")) {:allowed-functions allow}))))))
    (is (:dde? (accept/scan-formula "=app|topic!item")))
    (is (not (:external? (accept/scan-formula "=IF(A1=\"[x]\",1,0)")))))
  (testing "unknown and deprecated directives"
    (let [r (core/classify (assoc entries "Accounts.csv" (string/replace accounts-csv "pebblestream:not-blank" "pebblestream:no-such-thing")) {:allowed-functions allow})]
      (is (= [:unknown-directive] (map :code (:acceptance-errors r)))))
    (let [r (core/classify (assoc entries "Accounts.csv" (string/replace accounts-csv "pebblestream:not-blank" "pebblestream:group-sum(\"\"id\"\")")) {:allowed-functions allow})]
      (is (= :acceptable (:level r)))
      (is (some #{:deprecated-directive} (map :code (:warnings r))))))
  (testing "invalid archives"
    (let [codes (fn [e] (map :code (:errors (core/classify e))))]
      (is (= :invalid (:level (core/classify (assoc entries "Accounts.csv" (string/replace accounts-csv "psosf:=C2<=0" "@SUM(A1)"))))))
      (is (= [:path-traversal :unexpected-entry] (codes (assoc entries "../evil.csv" "x"))))
      (is (= [:unlisted-sheet] (codes (assoc entries "Other.csv" accounts-csv))))
      (is (some #{:field-count} (codes (assoc entries "Accounts.csv" (str accounts-csv "1,2\n")))))
      (is (= [:bad-license] (codes (assoc entries "manifest.json" (json/write-canonical (assoc manifest-map "license" {"name" "MIT"})))))))))

(deftest round-trip
  (let [{:keys [archive]} (core/read-entries entries)]
    (is (= entries (core/write-entries archive)))
    (let [tmp (java.io.File/createTempFile "psosf" ".psosf")]
      (core/write-file tmp archive)
      (is (= :acceptable (:level (core/classify-file tmp {:allowed-functions allow})))))))

(deftest legacy-1-0
  (let [r (core/classify {"Accounts.csv" (string/replace accounts-csv "psosf:=" "=")} {:allowed-functions allow})]
    (is (= :acceptable (:level r)))
    (is (some #{:version-1-0} (map :code (:warnings r))))
    (is (= {:kind :formula :text "=C2<=0"} (get-in r [:archive :sheets 0 :rows 0 3])))))

(deftest api-contract
  (is (= "acceptable" (get (api/classify entries {"allowed-functions" ["sum"]}) "level")))
  (is (= entries (api/write (get (api/read entries) "archive"))))
  (is (= "valid" (get (json/read-str (api/classify-json (json/write-compact {"entries" entries}))) "level"))))
