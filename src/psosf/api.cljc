;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.api
  "The cross-language contract. Every function takes and returns JSON-shaped
   data only: maps with string keys, vectors, strings, numbers, booleans and
   nil, with no keywords. Language adapters (JavaScript, Python, .NET, the
   command line) pass this data across their boundary unchanged.

   Keys are kebab-case, as in PSOSF's own JSON files.

   classify  entries [options] -> {\"level\" \"invalid\"|\"valid\"|\"acceptable\",
                                    \"errors\" [...], \"warnings\" [...],
                                    \"acceptance-errors\" [...], \"archive\" {...}}
   read      entries           -> {\"archive\" {...}, \"errors\" [...], \"warnings\" [...]}
   write     archive           -> entries

   entries is a map of archive path to file text. options may hold
   \"allowed-functions\", a list of function names.

   classify-json and friends take and return JSON text, for adapters that
   cross a C or process boundary."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as string]
            [psosf.archive]
            [psosf.core :as core]
            [psosf.json :as json]))

;; ========================================================
;; Clojure data -> JSON-shaped data
;; ========================================================

(defn- ->name [k] (if (keyword? k) (name k) k))

(defn- finding [m]
  (into {} (for [[k v] m :when (some? v)] [(name k) (->name v)])))

(defn- cell->json [{:keys [kind text]}] {"kind" (name kind) "text" text})

(defn- sheet->json [{:keys [name columns rows]}]
  {"name"    name
   "columns" (mapv (fn [{:keys [header type annotation style]}]
                     {"header"     header
                      "type"       type
                      "annotation" (mapv (fn [{:keys [kind text]}] {"kind" (clojure.core/name kind) "text" text}) annotation)
                      "style"      style})
                   columns)
   "rows"    (mapv #(mapv cell->json %) rows)})

(defn- archive->json [{:keys [version manifest sheets tests signatures]}]
  {"version"    version
   "manifest"   manifest
   "sheets"     (mapv sheet->json sheets)
   "tests"      (into {} (for [[t {:keys [json inputs expected]}] tests]
                           [t {"json" json "inputs" inputs "expected" expected}]))
   "signatures" signatures})

;; ========================================================
;; JSON-shaped data -> Clojure data
;; ========================================================

(defn- json->cell [{:strs [kind text]}] {:kind (keyword kind) :text (or text "")})

(defn- json->sheet [{:strs [name columns rows]}]
  {:name    name
   :columns (mapv (fn [{:strs [header type annotation style]}]
                    {:header     header
                     :type       type
                     :annotation (mapv (fn [{:strs [kind text]}] {:kind (keyword kind) :text text}) annotation)
                     :style      style})
                  columns)
   :rows    (mapv #(mapv json->cell %) rows)})

(defn- json->archive [{:strs [version manifest sheets tests signatures]}]
  {:version    version
   :manifest   manifest
   :sheets     (mapv json->sheet sheets)
   :tests      (into {} (for [[t {:strs [json inputs expected]}] tests]
                          [t {:json json :inputs inputs :expected expected}]))
   :signatures signatures})

;; ========================================================
;; The contract
;; ========================================================

(defn classify
  ([entries] (classify entries {}))
  ([entries options]
   (let [allowed (get options "allowed-functions")
         r       (core/classify entries (cond-> {} (some? allowed) (assoc :allowed-functions (set (map string/upper-case allowed)))))]
     {"level"             (name (:level r))
      "errors"            (mapv finding (:errors r))
      "warnings"          (mapv finding (:warnings r))
      "acceptance-errors" (mapv finding (:acceptance-errors r))
      "archive"           (archive->json (:archive r))})))

(defn read [entries]
  (let [{:keys [archive errors warnings]} (core/read-entries entries)]
    {"archive"  (archive->json archive)
     "errors"   (mapv finding errors)
     "warnings" (mapv finding warnings)}))

(defn write [archive] (core/write-entries (json->archive archive)))

;; JSON text in and out, for C ABI and command-line adapters.

(defn classify-json
  "{\"entries\": {...}, \"options\": {...}} -> result, as JSON text."
  [text]
  (let [{:strs [entries options]} (json/read-str text)]
    (json/write-compact (classify entries (or options {})))))

#?(:clj
   (defn classify-file
     "Reads and classifies a .psosf file (JVM). Same result shape as classify;
      an archive that cannot be read is reported as invalid."
     ([path] (classify-file path {}))
     ([path options]
      (let [{:keys [entries errors]} (psosf.archive/read-zip path)]
        (if (seq errors)
          {"level" "invalid" "errors" (mapv finding errors) "warnings" [] "acceptance-errors" []}
          (classify entries options))))))

(defn read-json
  "{\"entries\": {...}} -> {\"archive\" ..., \"errors\" ..., \"warnings\" ...}, as JSON text."
  [text] (json/write-compact (read (get (json/read-str text) "entries"))))
(defn write-json
  "{\"archive\": {...}} -> {\"entries\": {...}}, as JSON text."
  [text]
  (json/write-compact {"entries" (write (get (json/read-str text) "archive"))}))
