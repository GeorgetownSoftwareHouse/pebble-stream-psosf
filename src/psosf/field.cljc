;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.field
  "Field escaping. No field in a PSOSF worksheet or test file may begin with a
   character a spreadsheet application would evaluate (a trigger character),
   so any valid file is safe to open directly.

   Content is one of:
     {:kind :formula :text \"=A1+3\"}   written psosf:=A1+3
     {:kind :text    :text \"...\"}     a value, header, annotation or style

   Writing: text beginning with a trigger character, an apostrophe or psosf:
   gets a leading apostrophe; a formula gets the psosf: prefix. A plain number
   such as -400 is written as is."
  (:require [clojure.string :as string]))

(def prefix "psosf:")

(def trigger-chars #{\= \+ \- \@ \tab \return})

(def ^:private plain-number #"-?[0-9]+(\.[0-9]+)?")

(defn plain-number? [s] (boolean (re-matches plain-number s)))

(defn- needs-apostrophe? [s]
  (and (seq s)
       (or (and (contains? trigger-chars (first s)) (not (plain-number? s)))
           (= \' (first s))
           (string/starts-with? s prefix))))

(defn write-text
  "Escapes text for any field."
  [s]
  (if (needs-apostrophe? s) (str "'" s) s))

(defn write-content
  "Escapes cell content: a formula or text."
  [{:keys [kind text]}]
  (case kind
    :formula (do (when-not (string/starts-with? text "=")
                   (throw (ex-info "A formula must begin with =" {:text text})))
                 (str prefix text))
    :text    (write-text text)))

(defn read-field
  "Reads one field. Options: :cell? true when the field is a cell (line 5
   onward), the only place a formula may appear; :legacy? true for a
   version 1.0 archive. Returns content, or
   {:error code :message ... :fix ...}."
  [field {:keys [cell? legacy?]}]
  (cond
    ;; Version 1.0: a cell beginning with = is a formula, and only text
    ;; beginning with = or ' was escaped with an apostrophe.
    legacy?
    (cond (and cell? (string/starts-with? field "=")) {:kind :formula :text field}
          (string/starts-with? field "'")              {:kind :text :text (subs field 1)}
          :else                                        {:kind :text :text field})

    (string/starts-with? field prefix)
    (let [rest* (subs field (count prefix))]
      (cond
        (not cell?)
        {:error :formula-outside-cells
         :message "A psosf: formula may appear only in a cell (line 5 onward)."
         :fix (str "Write it as text with a leading apostrophe: '" field)}
        (not (string/starts-with? rest* "="))
        {:error :bad-formula-prefix
         :message "After psosf: a formula must begin with =."
         :fix (str "Write psosf:=" rest*)}
        :else {:kind :formula :text rest*}))

    (string/starts-with? field "'")
    {:kind :text :text (subs field 1)}

    (and (seq field) (contains? trigger-chars (first field)) (not (plain-number? field)))
    (let [c (first field)
          shown (case c \tab "a tab" \return "a carriage return" (str "\"" c "\""))]
      {:error :unescaped-trigger
       :message (str "A field may not begin with " shown ".")
       :fix (if cell?
              (str "Write psosf:" (if (= c \=) "" "=") field " if it is a formula, or '" field " if it is text.")
              (str "Write '" field "."))})

    :else {:kind :text :text field}))
