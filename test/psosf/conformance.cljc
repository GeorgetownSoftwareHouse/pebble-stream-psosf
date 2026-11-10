;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.conformance
  "Comparing a binding's result with a conformance case's expected result.
   Shared by the JVM and ClojureScript runners; bindings in other languages
   implement the same comparison (see conformance/README.md).")

(def compared-keys ["code" "file" "line" "column"])

(defn- projection
  "A finding reduced to the keys conformance compares; message and fix are
   human text and may change between versions."
  [finding]
  (into {} (for [k compared-keys :let [v (get finding k)] :when (some? v)] [k v])))

(defn mismatches
  "Differences between a classify result and a case's \"expect\" map, as
   strings; empty when the result conforms. Findings are compared as
   multisets, so order does not matter."
  [expected result]
  (concat
   (when (not= (get expected "level") (get result "level"))
     [(str "level: expected " (get expected "level") ", got " (get result "level"))])
   (for [k ["errors" "warnings" "acceptance-errors"]
         :let [want (frequencies (map projection (get expected k)))
               got  (frequencies (map projection (get result k)))]
         :when (not= want got)]
     (str k ": expected " (pr-str (get expected k)) ", got " (pr-str (mapv projection (get result k)))))))
