;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.parse-str
  (:require [clojure.string :as string]))

(defn- trim-all-whitespace
  "Trims whitespace, including non-breaking spaces, from both ends."
  [s]
  (-> s
      (string/replace #"^[\s\u00A0]+" "")
      (string/replace #"[\s\u00A0]+$" "")))

(defn- ambiguous-backslash
  "A warning for a backslash that escapes something other than a quote or a
   backslash. The backslash is still removed, as it always has been; the
   warning catches text such as a Windows path, where it was meant literally."
  [text index escaped]
  {:code    :ambiguous-backslash
   :index   index
   :message (str "The backslash before \"" escaped "\" in " text
                 " is read as an escape and removed. To keep the backslash, write it twice (\\\\).")})

(defn parse-str*
  "Parses a double-quoted string argument. Returns {:value v :warnings [...]},
   or nil if s is not a valid quoted string.

   Two escape styles are accepted inside the quotes:
     \\x  a backslash escapes the next character, so \\\" is a quote and \\\\ a backslash
     \"\"  a doubled quote is one quote, as in an Excel formula

   A quote that is neither escaped nor doubled makes the string invalid (nil),
   as does a backslash with nothing after it. Every string the backslash-only
   parser accepted parses to the same value here: that parser rejected any
   unescaped quote, so no string it accepted contained a doubled one.

   A backslash before any character other than \" or \\ is accepted, as before,
   and also produces an :ambiguous-backslash warning naming its index in the
   trimmed text."
  [s]
  (let [s (trim-all-whitespace s)
        n (count s)]
    (when (and (>= n 2) (= \" (nth s 0)) (= \" (nth s (dec n))))
      (let [end (dec n)]                          ; index of the closing quote
        (loop [i 1 out (transient []) warnings (transient [])]
          (if (>= i end)
            {:value (apply str (persistent! out)) :warnings (persistent! warnings)}
            (let [c (nth s i)]
              (cond
                (= \\ c) (when (< (inc i) end)    ; a trailing backslash escapes the closing quote
                           (let [escaped (nth s (inc i))]
                             (recur (+ i 2)
                                    (conj! out escaped)
                                    (if (or (= \" escaped) (= \\ escaped))
                                      warnings
                                      (conj! warnings (ambiguous-backslash s i escaped))))))
                (= \" c) (when (and (< (inc i) end) (= \" (nth s (inc i))))
                           (recur (+ i 2) (conj! out \") warnings))
                :else    (recur (inc i) (conj! out c) warnings)))))))))

(defn parse-str
  "Parses a double-quoted string argument, or returns nil if s is not one.
   Same value as parse-str*, without the warnings."
  [s]
  (:value (parse-str* s)))

(defn quote-str
  "Writes s as a quoted string argument that parse-str reads back as s, with no
   warnings. Quotes are doubled (the Excel form) and backslashes are escaped,
   since a lone backslash would otherwise escape the character after it."
  [s]
  (str \"
       (-> s
           (string/replace "\\" "\\\\")
           (string/replace "\"" "\"\""))
       \"))
