;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

;; Writes the conformance cases into conformance/cases/.
;;
;;   bb conformance:generate
;;
;; Every expected result here is written by hand from the PSOSF specification.
;; The generated files are committed; this script exists so they can be
;; regenerated consistently when a case is added or changed.

(ns generate
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [psosf.csv :as csv]
            [psosf.json :as json])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(def out-dir "conformance/cases")

;; ========================================================
;; Building blocks
;; ========================================================

(def base-manifest
  {"format" "psosf" "format-version" "1.1" "name" "Conformance" "sheets" ["Accounts"]})

(defn manifest [m] (json/write-canonical m))

(defn sheet-csv
  "A worksheet file from already-escaped fields."
  [{:keys [annotations types styles headers rows]}]
  (csv/write-records (concat [annotations types styles headers] rows)))

(def accounts
  {:annotations ["Account identifier." "pebblestream:not-blank\nCustomer type." "Balance." "pebblestream:fail"]
   :types       ["text" "text" "number" "boolean"]
   :styles      ["" "" "" ""]
   :headers     ["id" "type" "amount" "negative"]
   :rows        [["1" "individual" "435" "psosf:=C2<0"]
                 ["2" "corporate" "-400" "psosf:=C3<0"]]})

(defn accounts-with [f] (sheet-csv (f accounts)))

(def base-entries
  {"manifest.json" (manifest base-manifest)
   "Accounts.csv"  (sheet-csv accounts)})

(def allow {"allowed-functions" ["SUM"]})

(defn f
  "An expected finding."
  ([code] {"code" code})
  ([code file] {"code" code "file" file})
  ([code file line] {"code" code "file" file "line" line})
  ([code file line column] {"code" code "file" file "line" line "column" column}))

(defn expect [level & {:keys [errors warnings acceptance]}]
  {"level" level
   "errors" (vec errors)
   "warnings" (vec warnings)
   "acceptance-errors" (vec acceptance)})

(defn set-cell [sheet row col value] (assoc-in sheet [:rows row col] value))

;; ========================================================
;; Cases
;; ========================================================

(def cases
  [;; ---- acceptable and valid -------------------------------------------
   {:name "acceptable-basic"
    :description "A well-formed archive whose formulas call no functions."
    :entries base-entries :options allow
    :expect (expect "acceptable")
    :round-trip base-entries}

   {:name "valid-without-allow-list"
    :description "With no function allow-list, an archive can be valid but not acceptable."
    :entries base-entries :options {}
    :expect (expect "valid" :acceptance [(f "allow-list-not-configured")])}

   {:name "acceptable-allowed-function"
    :description "A formula calling a function on the allow-list."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(set-cell % 0 3 "psosf:=SUM(C2)<0")))
    :options allow
    :expect (expect "acceptable")}

   {:name "function-not-allowed"
    :description "A formula calling a function missing from the allow-list."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(set-cell % 0 3 "psosf:=WEBSERVICE(\"https://example.com\")<>\"\"")))
    :options allow
    :expect (expect "valid" :acceptance [(f "function-not-allowed" "Accounts.csv" 5 "negative")])}

   {:name "external-reference"
    :description "A formula that refers to another workbook."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(set-cell % 0 3 "psosf:=[Other.xlsx]Rates!A1<0")))
    :options allow
    :expect (expect "valid" :acceptance [(f "external-reference" "Accounts.csv" 5 "negative")])}

   {:name "dde"
    :description "A formula that uses DDE to reach another process."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(set-cell % 0 3 "psosf:=app|topic!item")))
    :options allow
    :expect (expect "valid" :acceptance [(f "dde" "Accounts.csv" 5 "negative")])}

   {:name "unknown-directive"
    :description "A directive name that PebbleScript does not define."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc-in % [:annotations 1] "pebblestream:no-such-thing")))
    :options allow
    :expect (expect "valid" :acceptance [(f "unknown-directive" "Accounts.csv" 1 "type")])}

   {:name "proposed-directive"
    :description "A directive that is specified but not yet available."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc-in % [:annotations 0] "pebblestream:http(\"FRED\", \"/x\")")))
    :options allow
    :expect (expect "valid" :acceptance [(f "directive-not-available" "Accounts.csv" 1 "id")])}

   {:name "deprecated-directive"
    :description "A deprecated directive is acceptable, with a warning."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc-in % [:annotations 0] "pebblestream:group-sum(\"id\")")))
    :options allow
    :expect (expect "acceptable" :warnings [(f "deprecated-directive" "Accounts.csv" 1 "id")])}

   {:name "version-1-0"
    :description "An archive without a manifest is read under version 1.0 rules, where formulas begin with =."
    :entries {"Accounts.csv" (accounts-with (fn [s] (-> s (set-cell 0 3 "=C2<0") (set-cell 1 3 "=C3<0"))))}
    :options allow
    :expect (expect "acceptable" :warnings [(f "version-1-0")])}

   {:name "escaped-text"
    :description "Text that a spreadsheet application could evaluate is escaped with an apostrophe; plain numbers are not."
    :entries (-> base-entries
                 (assoc "manifest.json" (manifest (assoc base-manifest "sheets" ["Accounts" "Notes"])))
                 (assoc "Notes.csv" (sheet-csv {:annotations [""] :types ["text"] :styles [""] :headers ["note"]
                                                :rows [["'=total"] ["'@SUM(A1)"] ["'+1 555 0100"] ["-400"] ["''quoted'"] ["'psosf:=x"]]})))
    :options allow
    :expect (expect "acceptable")
    :round-trip :same}

   {:name "manifest-optional-fields"
    :description "Namespace, description, license and export are all accepted."
    :entries (assoc base-entries "manifest.json"
                    (manifest (assoc base-manifest
                                     "namespace" "com.example.ledger"
                                     "description" "Conformance case."
                                     "license" {"name" "Apache License 2.0" "spdx" "Apache-2.0" "url" "https://www.apache.org/licenses/LICENSE-2.0"}
                                     "export" {"mode" "compact" "input-row-limit" 100})))
    :options allow
    :expect (expect "acceptable")
    :round-trip :same}

   {:name "tests-folder"
    :description "A well-formed tests folder."
    :entries (assoc base-entries
                    "tests/zero-balance/test.json" (json/write-canonical {"expect" "fail" "message" "negative"})
                    "tests/zero-balance/inputs/Accounts.csv" "id,type,amount\n1,individual,0\n")
    :options allow
    :expect (expect "acceptable")
    :round-trip :same}

   {:name "non-canonical-manifest"
    :description "A manifest that is valid JSON but not in canonical form is accepted, with a warning."
    :entries (assoc base-entries "manifest.json" (json/write-compact base-manifest))
    :options allow
    :expect (expect "acceptable" :warnings [(f "not-canonical" "manifest.json")])
    :round-trip base-entries}

   {:name "unnecessary-csv-quotes"
    :description "Quotes around fields that do not need them are accepted, and dropped when written."
    :entries (assoc base-entries "Accounts.csv" (string/replace (sheet-csv accounts) "id,type,amount,negative" "\"id\",\"type\",amount,negative"))
    :options allow
    :expect (expect "acceptable")
    :round-trip base-entries}

   ;; ---- invalid: worksheet files -------------------------------------------
   {:name "unescaped-trigger"
    :description "A cell that begins with a trigger character without escaping."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(set-cell % 0 3 "@SUM(A1)")))
    :options allow
    :expect (expect "invalid" :errors [(f "unescaped-trigger" "Accounts.csv" 5 "negative")])}

   {:name "formula-outside-cells"
    :description "A psosf: formula in an annotation."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc-in % [:annotations 0] "psosf:=1")))
    :options allow
    :expect (expect "invalid" :errors [(f "formula-outside-cells" "Accounts.csv" 1 "id")])}

   {:name "bad-formula-prefix"
    :description "psosf: followed by something other than =."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(set-cell % 0 3 "psosf:C2<0")))
    :options allow
    :expect (expect "invalid" :errors [(f "bad-formula-prefix" "Accounts.csv" 5 "negative")])}

   {:name "field-count"
    :description "A line with fewer fields than there are headers."
    :entries (assoc base-entries "Accounts.csv" (str (sheet-csv accounts) "3,x\n"))
    :options allow
    :expect (expect "invalid" :errors [(f "field-count" "Accounts.csv" 7)])}

   {:name "missing-preamble"
    :description "A worksheet file shorter than its four preamble lines."
    :entries (assoc base-entries "Accounts.csv" "a,b\ntext,text\n")
    :options allow
    :expect (expect "invalid" :errors [(f "missing-preamble" "Accounts.csv")])}

   {:name "header-problems"
    :description "A blank header and a duplicated header."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc % :headers ["id" "" "amount" "amount"])))
    :options allow
    :expect (expect "invalid" :errors [(f "blank-header" "Accounts.csv" 4)
                                       (f "duplicate-header" "Accounts.csv" 4 "amount")])}

   {:name "bad-type"
    :description "A type other than text, number, boolean or date."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc-in % [:types 2] "currency")))
    :options allow
    :expect (expect "invalid" :errors [(f "bad-type" "Accounts.csv" 2 "amount")])}

   {:name "bad-style"
    :description "A style without a style object."
    :entries (assoc base-entries "Accounts.csv" (accounts-with #(assoc-in % [:styles 2] "{\"name\":\"x\"}")))
    :options allow
    :expect (expect "invalid" :errors [(f "bad-style" "Accounts.csv" 3 "amount")])}

   {:name "bad-csv"
    :description "A worksheet file that is not valid CSV."
    :entries (assoc base-entries "Accounts.csv" "\"unterminated,b\ntext,text\n")
    :options allow
    :expect (expect "invalid" :errors [(f "bad-csv" "Accounts.csv")])}

   ;; ---- invalid: manifest ----------------------------------------------------
   {:name "manifest-required-fields"
    :description "Wrong format, unsupported version and no name."
    :entries (assoc base-entries "manifest.json" (manifest {"format" "xlsx" "format-version" "9" "sheets" ["Accounts"]}))
    :options allow
    :expect (expect "invalid" :errors [(f "bad-format" "manifest.json")
                                       (f "unsupported-version" "manifest.json")
                                       (f "missing-name" "manifest.json")])}

   {:name "manifest-field-shapes"
    :description "A malformed namespace, license and export."
    :entries (assoc base-entries "manifest.json"
                    (manifest (assoc base-manifest "namespace" "Com.Example" "license" {"name" "MIT"} "export" {"mode" "tiny"})))
    :options allow
    :expect (expect "invalid" :errors [(f "bad-namespace" "manifest.json")
                                       (f "bad-license" "manifest.json")
                                       (f "bad-export" "manifest.json")])}

   {:name "manifest-not-json"
    :description "A manifest that is not valid JSON."
    :entries (assoc base-entries "manifest.json" "{oops")
    :options allow
    :expect (expect "invalid" :errors [(f "bad-json" "manifest.json")])}

   {:name "unknown-manifest-key"
    :description "An unknown manifest key is ignored, with a warning."
    :entries (assoc base-entries "manifest.json" (manifest (assoc base-manifest "colour" "teal")))
    :options allow
    :expect (expect "acceptable" :warnings [(f "unknown-key" "manifest.json")])}

   {:name "sheet-list-mismatch"
    :description "A listed sheet with no file, and a sheet file that is not listed."
    :entries (assoc base-entries
                    "manifest.json" (manifest (assoc base-manifest "sheets" ["Accounts" "Missing"]))
                    "Other.csv" (sheet-csv accounts))
    :options allow
    :expect (expect "invalid" :errors [(f "missing-sheet-file" "manifest.json")
                                       (f "unlisted-sheet" "Other.csv")])}

   ;; ---- invalid: archive structure ----------------------------------------
   {:name "hostile-names"
    :description "Path traversal, a file outside the format, and names that differ only in case."
    :entries (assoc base-entries
                    "../evil.csv" "x"
                    "tests/x/notes.txt" "x"
                    "Accounts.CSV" "x")
    :options allow
    :expect (expect "invalid" :errors [(f "path-traversal" "../evil.csv")
                                       (f "unexpected-entry" "../evil.csv")
                                       (f "unexpected-entry" "tests/x/notes.txt")
                                       (f "unexpected-entry" "Accounts.CSV")
                                       (f "case-collision" "Accounts.CSV")
                                       (f "missing-test-json" "tests/x/test.json")])}

   {:name "test-problems"
    :description "A bad test.json, a test input for an unknown sheet, and a formula in a test file."
    :entries (assoc base-entries
                    "tests/t1/test.json" (json/write-canonical {"expect" "maybe"})
                    "tests/t1/inputs/Ghost.csv" "id\n1\n"
                    "tests/t1/expected/Accounts.csv" "id\npsosf:=1\n")
    :options allow
    :expect (expect "invalid" :errors [(f "bad-test" "tests/t1/test.json")
                                       (f "unknown-test-sheet" "tests/t1/inputs/Ghost.csv")
                                       (f "formula-in-test" "tests/t1/expected/Accounts.csv" 2)])}

   ;; ---- ZIP containers ---------------------------------------------------------
   {:name "zip-acceptable"
    :description "A well-formed archive read from its ZIP container."
    :zip base-entries :options allow
    :expect (expect "acceptable")}

   {:name "zip-not-a-zip"
    :description "Bytes that are not a ZIP archive."
    :zip-bytes (.getBytes "this is not a zip archive" "UTF-8") :options allow
    :expect (expect "invalid" :errors [(f "bad-zip")])}

   {:name "zip-compression-ratio"
    :description "An entry that expands more than 100 times its compressed size."
    :zip (assoc base-entries "Accounts.csv"
                (sheet-csv (assoc accounts :rows (vec (repeat 40000 ["1" "individual" "435" "psosf:=C2<0"])))))
    :options allow
    :expect (expect "invalid" :errors [(f "compression-ratio" "Accounts.csv")])}])

;; ========================================================
;; Writing
;; ========================================================

(defn ->zip-bytes [entries]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (with-open [out (ZipOutputStream. buf)]
      (doseq [[p text] (sort-by key entries)]
        (.putNextEntry out (ZipEntry. ^String p))
        (.write out (.getBytes ^String text "UTF-8"))
        (.closeEntry out)))
    (.toByteArray buf)))

(defn write-case [{:keys [name description entries zip zip-bytes options expect round-trip]}]
  (let [dir (io/file out-dir name)]
    (.mkdirs dir)
    (when (or zip zip-bytes)
      (with-open [o (io/output-stream (io/file dir "archive.psosf"))]
        (.write o ^bytes (or zip-bytes (->zip-bytes zip)))))
    (spit (io/file dir "case.json")
          (json/write-canonical
           (cond-> {"name" name
                    "description" description
                    "options" options
                    "expect" expect}
             entries    (assoc "input" {"entries" entries})
             (or zip zip-bytes) (assoc "input" {"zip" "archive.psosf"})
             round-trip (assoc "round-trip" {"entries" (if (= :same round-trip) entries round-trip)}))))))

(defn -main []
  (doseq [c cases] (write-case c))
  (spit (io/file "conformance" "cases.json")
        (json/write-canonical {"version" "1" "cases" (mapv :name cases)}))
  (println "Wrote" (count cases) "cases to" out-dir))

(-main)
