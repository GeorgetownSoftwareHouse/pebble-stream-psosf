;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.js-test
  "The directive check through its JavaScript adapter."
  (:require [cljs.test :refer [deftest is testing]]
            [pebblestream.directives.js :as djs]
            [psosf.core :as psosf]
            [psosf.js :as psjs]
            [psosf.model :as m]))

(def archive
  (-> (m/archive "Distributions")
      (m/add-sheet (-> (m/sheet "ClassNII") (m/add-column {:header "Fund ID"}) (m/add-column {:header "NII ($)"})
                       (m/add-row ["F1" "100"])))
      (m/add-sheet (-> (m/sheet "Totals") (m/add-column {:header "Fund ID"}) (m/add-column {:header "NII ($)"})
                       (m/add-annotation-line "Fund ID" "pebblestream:from(ClassNII)")
                       (m/add-annotation-line "Fund ID" "pebblestream:group(\"Fund ID\")")
                       (m/add-annotation-line "Fund ID" "pebblestream:sum(\"NII ($)\")")
                       (m/add-row ["F1" "100"])))))

(def broken
  (m/update-sheet archive "Totals" m/set-annotation "Fund ID"
                  ["pebblestream:from(ClassNII)" "pebblestream:group(\"Fund ID\")" "pebblestream:sum(\"NII\")"]))

(defn- codes [report] (set (map #(.-code %) (.-errors report))))

(deftest check-entries-and-zip
  (let [ok (djs/check (clj->js (psosf/write-entries archive)) #js {"allowed-functions" #js ["SUM"]})]
    (is (true? (.-passed ok)))
    (is (= "acceptable" (.-level ok))))
  (let [r (djs/check-zip (psjs/zip (clj->js (psosf/write-entries broken))))
        e (first (filter #(= "unknown-header" (.-code %)) (.-errors r)))]
    (is (false? (.-passed r)))
    (is (= #{"unknown-header"} (codes r)))
    (is (= "NII" (.-argument e)))
    (is (= "Did you mean \"NII ($)\"?" (.-fix e))))
  (is (= "invalid" (.-level (djs/check-zip (js/Uint8Array. #js [1 2 3]))))))

(deftest parse-line
  (let [p (djs/parse-line "pebblestream:sort(\"Last\", \"Date\":desc, Inputs!B2)")]
    (is (= "sort" (.-directive p)))
    (is (= "paren" (.-syntax p)))
    (is (= ["Last" {"column" "Date" "order" "desc"} {"ref" "Inputs!B2"}] (js->clj (.-args p)))))
  (is (= "colon" (.-syntax (djs/parse-line "pebblestream:hint:key:value"))))
  (is (string? (.-error (djs/parse-line "pebblestream:join(\"Rates\"")))))
