;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.conformance-js
  "Runs every conformance case against the JavaScript adapter, and checks that
   every result matches the API's JSON Schema. Run with: npm test"
  (:require ["fs" :as fs]
            ["path" :as path]
            ["ajv/dist/2020.js" :as ajv-module]
            [cljs.test :refer [deftest is testing]]
            [psosf.conformance :as conformance]
            [psosf.js :as psjs]))

(def root "conformance")

(defn- read-json [& parts]
  (js->clj (js/JSON.parse (fs/readFileSync (apply path/join parts) "utf8"))))

(def schema (js/JSON.parse (fs/readFileSync "schema/psosf-api-v1.schema.json" "utf8")))

(def ajv
  (let [Ajv (or (.-default ajv-module) ajv-module)]
    (doto (new Ajv #js {:allErrors true :strict false}) (.addSchema schema "api"))))

(defn- validate [def-name value]
  (let [v (.getSchema ajv (str "api#/$defs/" def-name))]
    (when-not (v value)
      (js/JSON.stringify (.-errors v)))))

(deftest conformance-cases
  (doseq [name (get (read-json root "cases.json") "cases")]
    (let [c       (read-json root "cases" name "case.json")
          input   (get c "input")
          options (clj->js (get c "options"))
          result  (if-let [zip (get input "zip")]
                    (psjs/classify-zip (fs/readFileSync (path/join root "cases" name zip)) options)
                    (psjs/classify (clj->js (get input "entries")) options))]
      (testing name
        (is (nil? (validate "classify-result" result)) (str name ": classify result matches the schema"))
        (is (empty? (conformance/mismatches (get c "expect") (js->clj result))) name)
        (when-let [want (get-in c ["round-trip" "entries"])]
          (let [read-result (psjs/read (clj->js (get input "entries")))
                written     (psjs/write (.-archive read-result))]
            (is (nil? (validate "read-result" read-result)) (str name ": read result matches the schema"))
            (is (nil? (validate "write-result" #js {:entries written})) (str name ": write result matches the schema"))
            (is (= want (js->clj written)) (str name ": round trip"))))))))
