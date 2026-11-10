;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.manifest
  "manifest.json: structure, namespaces, license and canonical form."
  (:require [clojure.string :as string]
            [psosf.json :as json]))

(def supported-versions #{"1.1"})

(def ^:private known-keys
  #{"format" "format-version" "name" "namespace" "description" "sheets" "license" "export"})

(def ^:private namespace-re #"[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*")

(defn valid-sheet-name?
  "Excel's rules: 1 to 31 characters, none of \\ / ? * [ ] :"
  [s]
  (boolean (and (string? s) (re-matches #"[^\\/?*\[\]:]{1,31}" s))))

(defn- err [code message & [fix]]
  (cond-> {:code code :file "manifest.json" :message message} fix (assoc :fix fix)))

(defn check
  "Structural errors and warnings for a parsed manifest. Returns
   {:errors [...] :warnings [...]}."
  [m]
  (if-not (map? m)
    {:errors [(err :bad-manifest "manifest.json must be a JSON object.")]}
    (let [sheets  (get m "sheets")
          license (get m "license")
          export  (get m "export")
          ns*     (get m "namespace")]
      {:errors
       (vec
        (concat
         (when-not (= "psosf" (get m "format"))
           [(err :bad-format "\"format\" must be \"psosf\".")])
         (when-not (contains? supported-versions (get m "format-version"))
           [(err :unsupported-version (str "Unsupported format-version " (pr-str (get m "format-version")) ".")
                 "This library reads format-version 1.1.")])
         (when-not (and (string? (get m "name")) (not (string/blank? (get m "name"))))
           [(err :missing-name "\"name\" is required and must be text.")])
         (cond
           (not (and (vector? sheets) (seq sheets) (every? string? sheets)))
           [(err :bad-sheets "\"sheets\" must list at least one worksheet name.")]
           :else
           (concat
            (for [s sheets :when (not (valid-sheet-name? s))]
              (err :bad-sheet-name (str "\"" s "\" is not a valid worksheet name: 1 to 31 characters, none of \\ / ? * [ ] :")))
            (for [[s n] (frequencies (map string/lower-case sheets)) :when (> n 1)]
              (err :duplicate-sheet (str "The worksheet \"" s "\" is listed " n " times (names are compared without regard to case).")))))
         (when (and (some? ns*) (not (and (string? ns*) (re-matches namespace-re ns*))))
           [(err :bad-namespace (str (pr-str ns*) " is not a valid namespace.")
                 "Use dot-separated lowercase segments, such as com.example.ledger.")])
         (when (some? license)
           (when-not (and (map? license)
                          (string? (get license "name")) (not (string/blank? (get license "name")))
                          (string? (get license "url")) (not (string/blank? (get license "url")))
                          (every? #{"name" "url" "spdx"} (keys license))
                          (or (nil? (get license "spdx")) (string? (get license "spdx"))))
             [(err :bad-license "\"license\" must have a name and a url, and may have an spdx identifier.")]))
         (when (some? export)
           (when-not (and (map? export)
                          (contains? #{"compact" "full"} (get export "mode"))
                          (every? #{"mode" "input-row-limit"} (keys export))
                          (or (nil? (get export "input-row-limit"))
                              (and (integer? (get export "input-row-limit")) (pos? (get export "input-row-limit")))))
             [(err :bad-export "\"export\" must have a mode of compact or full, and may have a positive input-row-limit.")]))
         (when (and (some? (get m "description")) (not (string? (get m "description"))))
           [(err :bad-description "\"description\" must be text.")])))
       :warnings
       (vec (for [k (sort (keys m)) :when (not (contains? known-keys k))]
              (err :unknown-key (str "Unknown manifest key \"" k "\"; it will be ignored."))))})))

(defn canonical? [text m] (= text (json/write-canonical m)))
