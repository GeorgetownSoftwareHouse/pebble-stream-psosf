;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.json
  "A small, dependency-free JSON reader and writer.

   Objects read as maps with string keys, so documents can be written back in
   canonical form exactly. Two writers:

     (write-canonical x)  two-space indentation, one key or element per line,
                          keys sorted at every level, final line feed; the form
                          PSOSF requires for manifest.json, signatures.json and
                          test.json
     (write-compact x)    no insignificant whitespace, keys sorted; the form PSOSF
                          requires for line 3 styles

   Non-ASCII characters are written as UTF-8, never escaped."
  (:require [clojure.string :as string]))

;; ========================================================
;; Reading
;; ========================================================

(defn- code
  "A character's code point. (int c) gives 0 for every character in
   ClojureScript, where characters are strings."
  [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn- fail [msg i] (throw (ex-info (str msg " at character " i) {:psosf/json-error msg :index i})))

(defn- ws? [c] (contains? #{\space \tab \newline \return} c))

(defn- skip-ws [s i]
  (loop [i i] (if (and (< i (count s)) (ws? (nth s i))) (recur (inc i)) i)))

(declare read-value)

(defn- hex-val [s i]
  (when (> (+ i 4) (count s)) (fail "Truncated \\u escape" i))
  (let [h (subs s i (+ i 4))]
    (when-not (re-matches #"[0-9a-fA-F]{4}" h) (fail "Bad \\u escape" i))
    #?(:clj (Integer/parseInt h 16) :cljs (js/parseInt h 16))))

(defn- read-string* [s i]
  ;; i is just after the opening quote
  (loop [i i out (transient [])]
    (when (>= i (count s)) (fail "Unterminated string" i))
    (let [c (nth s i)]
      (cond
        (= c \") [(apply str (persistent! out)) (inc i)]
        (= c \\) (let [e (nth s (inc i) nil)]
                   (case e
                     \" (recur (+ i 2) (conj! out \"))
                     \\ (recur (+ i 2) (conj! out \\))
                     \/ (recur (+ i 2) (conj! out \/))
                     \b (recur (+ i 2) (conj! out \backspace))
                     \f (recur (+ i 2) (conj! out \formfeed))
                     \n (recur (+ i 2) (conj! out \newline))
                     \r (recur (+ i 2) (conj! out \return))
                     \t (recur (+ i 2) (conj! out \tab))
                     \u (recur (+ i 6) (conj! out (char (hex-val s (+ i 2)))))
                     (fail "Bad escape" i)))
        (< (code c) 0x20) (fail "Control character in string" i)
        :else (recur (inc i) (conj! out c))))))

(defn- read-number [s i]
  (let [m (re-find #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?" (subs s i))]
    (when-not m (fail "Bad number" i))
    (let [text (first m)
          n    (if (or (nth m 2) (nth m 3))
                 #?(:clj (Double/parseDouble text) :cljs (js/parseFloat text))
                 #?(:clj (Long/parseLong text) :cljs (js/parseInt text 10)))]
      [n (+ i (count text))])))

(defn- read-literal [s i word value]
  (if (= word (subs s i (min (count s) (+ i (count word)))))
    [value (+ i (count word))]
    (fail "Unexpected token" i)))

(defn- read-array [s i]
  (let [i (skip-ws s i)]
    (if (= \] (nth s i nil))
      [[] (inc i)]
      (loop [i i out (transient [])]
        (let [[v i] (read-value s i)
              i     (skip-ws s i)]
          (case (nth s i nil)
            \, (recur (inc i) (conj! out v))
            \] [(persistent! (conj! out v)) (inc i)]
            (fail "Expected , or ]" i)))))))

(defn- read-object [s i]
  (let [i (skip-ws s i)]
    (if (= \} (nth s i nil))
      [{} (inc i)]
      (loop [i i out {}]
        (let [i     (skip-ws s i)
              _     (when-not (= \" (nth s i nil)) (fail "Expected a string key" i))
              [k i] (read-string* s (inc i))
              i     (skip-ws s i)
              _     (when-not (= \: (nth s i nil)) (fail "Expected :" i))
              [v i] (read-value s (inc i))
              _     (when (contains? out k) (fail (str "Duplicate key \"" k "\"") i))
              out   (assoc out k v)
              i     (skip-ws s i)]
          (case (nth s i nil)
            \, (recur (inc i) out)
            \} [out (inc i)]
            (fail "Expected , or }" i)))))))

(defn- read-value [s i]
  (let [i (skip-ws s i)]
    (case (nth s i nil)
      \{ (read-object s (inc i))
      \[ (read-array s (inc i))
      \" (read-string* s (inc i))
      \t (read-literal s i "true" true)
      \f (read-literal s i "false" false)
      \n (read-literal s i "null" nil)
      nil (fail "Unexpected end of input" i)
      (read-number s i))))

(defn read-str
  "Parses a JSON document. Throws ex-info with :psosf/json-error on bad input."
  [s]
  (let [[v i] (read-value s 0)
        i     (skip-ws s i)]
    (when (< i (count s)) (fail "Unexpected content after the document" i))
    v))

;; ========================================================
;; Writing
;; ========================================================

(defn- hex4 [n]
  (let [h (string/upper-case #?(:clj (Integer/toHexString n) :cljs (.toString n 16)))]
    (str (apply str (repeat (- 4 (count h)) "0")) h)))

(defn- escape-str [s]
  (str \"
       (apply str
              (map (fn [c]
                     (case c
                       \" "\\\""
                       \\ "\\\\"
                       \newline "\\n"
                       \return "\\r"
                       \tab "\\t"
                       \backspace "\\b"
                       \formfeed "\\f"
                       (if (< (code c) 0x20) (str "\\u" (hex4 (code c))) (str c))))
                   s))
       \"))

(defn- write-scalar [x]
  (cond
    (nil? x)     "null"
    (true? x)    "true"
    (false? x)   "false"
    (string? x)  (escape-str x)
    (keyword? x) (escape-str (name x))
    (number? x)  (str x)
    :else (throw (ex-info (str "Cannot write " (pr-str x) " as JSON") {:value x}))))

(defn- sorted-entries [m]
  (sort-by first (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))

(defn write-compact
  "Compact JSON with keys sorted at every level."
  [x]
  (cond
    (map? x)        (str "{" (string/join "," (map (fn [[k v]] (str (escape-str k) ":" (write-compact v))) (sorted-entries x))) "}")
    (sequential? x) (str "[" (string/join "," (map write-compact x)) "]")
    :else           (write-scalar x)))

(defn- write-indented [x depth]
  (let [pad  (apply str (repeat (* 2 (inc depth)) " "))
        pad0 (apply str (repeat (* 2 depth) " "))]
    (cond
      (and (map? x) (seq x))
      (str "{\n"
           (string/join ",\n" (map (fn [[k v]] (str pad (escape-str k) ": " (write-indented v (inc depth)))) (sorted-entries x)))
           "\n" pad0 "}")
      (map? x) "{}"
      (and (sequential? x) (seq x))
      (str "[\n" (string/join ",\n" (map #(str pad (write-indented % (inc depth))) x)) "\n" pad0 "]")
      (sequential? x) "[]"
      :else (write-scalar x))))

(defn write-canonical
  "Canonical JSON: two-space indentation, one key or element per line, keys
   sorted at every level, and a final line feed."
  [x]
  (str (write-indented x 0) "\n"))
