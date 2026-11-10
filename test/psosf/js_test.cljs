;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

;; The portable code and the JavaScript adapter under ClojureScript.
;; Run with: npm test
(ns psosf.js-test
  (:require [clojure.string :as string]
            [cljs.test :refer [deftest is testing]]
            [psosf.api :as api]
            [psosf.js :as psjs]
            [psosf.json :as json]))

(def accounts-csv
  (str "Account identifier from the general ledger.,\"pebblestream:not-blank\nCustomer classification: individual or corporate.\",Balance in US dollars.,\"pebblestream:fail\nStops the run when a balance is zero or negative.\"\n"
       "text,text,number,boolean\n"
       ",,,\n"
       "id,type,amount,amount is not positive\n"
       "1,individual,435,psosf:=C2<=0\n"
       "2,corporate,-400,psosf:=C3<=0\n"))

(def manifest-map
  {"description" "Validates ledger accounts — en dash, café." "format" "psosf" "format-version" "1.1"
   "license" {"name" "Apache License 2.0" "spdx" "Apache-2.0" "url" "https://www.apache.org/licenses/LICENSE-2.0"}
   "name" "Account Balances" "namespace" "com.example.ledger" "sheets" ["Accounts"]})

(def entries {"manifest.json" (json/write-canonical manifest-map) "Accounts.csv" accounts-csv})

(deftest portable-json
  (let [doc {"b" [1 2.5 "x\"y\n"] "a" {"z" true "y" nil} "é" "ü"}]
    (is (= doc (json/read-str (json/write-canonical doc))))
    (is (= "\"\\u0001\"" (json/write-compact "\u0001")))))

(deftest api-levels
  (is (= "valid" (get (api/classify entries) "level")))
  (is (= "acceptable" (get (api/classify entries {"allowed-functions" ["sum"]}) "level")))
  (let [bad (api/classify (assoc entries "Accounts.csv" (string/replace accounts-csv "psosf:=C2<=0" "@SUM(A1)")))]
    (is (= "invalid" (get bad "level")))
    (is (= "unescaped-trigger" (get-in bad ["errors" 0 "code"])))))

(deftest api-round-trip
  (let [archive (get (api/read entries) "archive")]
    (is (= "formula" (get-in archive ["sheets" 0 "rows" 0 3 "kind"])))
    (is (= entries (api/write archive)))
    (is (= entries (get (json/read-str (api/write-json (json/write-compact {"archive" archive}))) "entries")
           (api/write archive)))))

(deftest js-adapter
  (let [js-entries (clj->js entries)
        bytes      (psjs/zip js-entries)
        result     (psjs/classify-zip bytes #js {"allowed-functions" #js ["SUM"]})]
    (is (instance? js/Uint8Array bytes))
    (is (= "acceptable" (.-level result)))
    (is (= entries (js->clj (.-entries (psjs/unzip bytes)))))
    (is (= "unexpected-entry" (aget (.-errors (psjs/classify (clj->js (assoc entries "notes.txt" "x")))) 0 "code")))
    (is (= "entry-too-large" (aget (.-errors (psjs/unzip bytes #js {"maxEntryBytes" 10})) 0 "code")))))
