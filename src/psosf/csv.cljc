;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.csv
  "RFC 4180 CSV as PSOSF uses it: comma-separated, LF line endings (CRLF
   accepted on reading), fields quoted only when they contain a comma, a quote
   or a line break, and quotes inside a quoted field doubled."
  (:require [clojure.string :as string]))

(defn- read-quoted
  "Reads a quoted field starting just after its opening quote. Returns
   [text index-after-closing-quote]."
  [s i]
  (let [n (count s)]
    (loop [i i out (transient [])]
      (cond
        (>= i n) (throw (ex-info "Unterminated quoted field" {:psosf/csv-error :unterminated}))
        (not= \" (nth s i)) (recur (inc i) (conj! out (nth s i)))
        (= \" (nth s (inc i) nil)) (recur (+ i 2) (conj! out \"))
        :else [(apply str (persistent! out)) (inc i)]))))

(defn- read-plain
  "Reads an unquoted field. Returns [text index-of-delimiter-or-end]."
  [s i]
  (let [n (count s)]
    (loop [j i]
      (if (or (>= j n) (contains? #{\, \newline \return} (nth s j)))
        [(subs s i j) j]
        (recur (inc j))))))

(defn read-records
  "Parses CSV text into a vector of records, each a vector of strings.
   A final line ending is optional. Throws ex-info with :psosf/csv-error on an
   unterminated quoted field or text after a closing quote."
  [s]
  (let [n (count s)]
    (loop [i 0 record [] records []]
      (if (>= i n)
        (if (seq record) (conj records record) records)
        (let [[text j] (if (= \" (nth s i)) (read-quoted s (inc i)) (read-plain s i))
              record   (conj record text)
              c        (nth s j nil)]
          (cond
            (nil? c)
            (conj records record)

            (= c \,)
            (if (>= (inc j) n)
              (conj records (conj record ""))          ; trailing comma at end of input
              (recur (inc j) record records))

            (= c \newline)
            (recur (inc j) [] (conj records record))

            (and (= c \return) (= \newline (nth s (inc j) nil)))
            (recur (+ j 2) [] (conj records record))

            :else
            (throw (ex-info "Text after a closing quote" {:psosf/csv-error :after-quote :index j}))))))))

(defn- needs-quotes? [f] (boolean (re-find #"[,\"\n\r]" f)))

(defn write-field [f]
  (if (needs-quotes? f) (str \" (string/replace f "\"" "\"\"") \") f))

(defn write-records
  "Writes records as CSV, quoting only where required, each line ending in LF."
  [records]
  (apply str (map #(str (string/join "," (map write-field %)) "\n") records)))
