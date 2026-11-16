;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.parse
  "Parses a line of textual PebbleScript into a directive:

     pebblestream:join(\"Rates\", \"id\", \"rate-id\")
     => {:directive :join :syntax :paren :args [\"Rates\" \"id\" \"rate-id\"]}

   Accepted forms: bare (pebblestream:unique), parentheses
   (pebblestream:sum(\"amount\", \"total\")) and the deprecated colon form
   (pebblestream:hint:key:value, pebblestream:allowed-values:a:b), whose
   arguments are separated by colons only.

   Arguments parse to:
     \"text\" or Name           a string (quoted strings use the escape rules
                              in pebblestream.parse-str; unquoted names have
                              no spaces)
     'Sheet Name'             a string, Excel's single-quoted sheet notation
     12, -0.5                 a number
     :latest                  a keyword (flags)
     Inputs!B2, 'Rates'!$C$4  {:ref \"Inputs!B2\"}, a cell reference; it must
                              name its worksheet, so B2 alone is a name
     \"Date\":desc              {:column \"Date\" :order :desc}, a sort column
     \"<=\"&\"Balance\"           {:operator \"<=\" :column \"Balance\"}
     \"<\"&Inputs!B2            {:operator \"<\" :ref \"Inputs!B2\"}"
  (:require [clojure.string :as string]
            [pebblestream.parse-str :as parse-str]))

(def prefix "pebblestream:")

(defn directive-line?
  "True when an annotation line is a directive (begins with pebblestream:)."
  [line]
  (string/starts-with? (string/triml line) prefix))

;; ========================================================
;; Splitting arguments
;; ========================================================

(defn- split-top-level
  "Splits text on separator characters that are outside quotes and brackets."
  [text separators]
  (let [n (count text)]
    (loop [i 0 depth 0 quote nil start 0 out []]
      (if (>= i n)
        (conj out (subs text start))
        (let [c (nth text i)]
          (cond
            ;; inside a quoted string: a backslash escapes, a doubled quote is literal
            (and quote (= c \\) (= quote \")) (recur (+ i 2) depth quote start out)
            (and quote (= c quote) (= quote (nth text (inc i) nil))) (recur (+ i 2) depth quote start out)
            (and quote (= c quote)) (recur (inc i) depth nil start out)
            quote (recur (inc i) depth quote start out)
            (#{\" \'} c) (recur (inc i) depth c start out)
            (#{\( \[ \{} c) (recur (inc i) (inc depth) quote start out)
            (#{\) \] \}} c) (recur (inc i) (max 0 (dec depth)) quote start out)
            (and (zero? depth) (contains? separators c)) (recur (inc i) depth quote (inc i) (conj out (subs text start i)))
            :else (recur (inc i) depth quote start out)))))))

;; ========================================================
;; One argument
;; ========================================================

(def ^:private cell-ref
  "A cell reference names its worksheet: Inputs!B2 or 'Rate Table'!$C$4."
  #"^(?:'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!\$?[A-Za-z]{1,3}\$?[0-9]+$")

(defn- quoted-prefix
  "Splits text beginning with a double-quoted string into [string-token rest]."
  [text]
  (let [n (count text)]
    (loop [i 1]
      (cond
        (>= i n) [text ""]
        (= \\ (nth text i)) (recur (+ i 2))
        (and (= \" (nth text i)) (= \" (nth text (inc i) nil))) (recur (+ i 2))
        (= \" (nth text i)) [(subs text 0 (inc i)) (subs text (inc i))]
        :else (recur (inc i))))))

(defn- unquote-sheet [s]
  (string/replace (subs s 1 (dec (count s))) "''" "'"))

(defn- read-scalar
  "An unquoted or single-quoted token: a number, keyword, cell reference or name."
  [token]
  (cond
    (re-matches #"-?[0-9]+(\.[0-9]+)?" token)
    {:value #?(:clj (if (string/includes? token ".") (Double/parseDouble token) (Long/parseLong token))
               :cljs (js/parseFloat token))}
    (re-matches #":[A-Za-z0-9-]+" token) {:value (keyword (subs token 1))}
    (re-matches cell-ref token)          {:value {:ref token}}
    (re-matches #"'(?:[^']|'')+'" token) {:value (unquote-sheet token)}
    ;; a comma can only reach here in the colon form, where it is not a separator
    (re-matches #"[^\s\"'()&]+" token)   {:value token}
    :else {:error (str "Cannot read the argument " token ".")}))

(defn- read-arg
  "Reads one argument. Returns {:value v :warnings [...]} or {:error message}."
  [raw]
  (let [token (string/trim raw)]
    (cond
      (= "" token) {:error "An argument is empty."}

      (string/starts-with? token "\"")
      (let [[q rest*] (quoted-prefix token)
            {:keys [value warnings] :as parsed} (parse-str/parse-str* q)
            rest* (string/trim rest*)]
        (cond
          (nil? parsed) {:error (str "The string " q " is not properly quoted.")}
          (= "" rest*) {:value value :warnings warnings}
          ;; "Date":desc
          (re-matches #":(asc|desc)" rest*) {:value {:column value :order (keyword (subs rest* 1))} :warnings warnings}
          ;; "<="&"Balance" or "<"&Inputs!B2
          (string/starts-with? rest* "&")
          (let [rhs (string/trim (subs rest* 1))]
            (cond
              (string/starts-with? rhs "\"")
              (let [{v :value w :warnings :as p} (parse-str/parse-str* rhs)]
                (if p
                  {:value {:operator value :column v} :warnings (into warnings w)}
                  {:error (str "Cannot read " rhs " after &.")}))
              (re-matches cell-ref rhs) {:value {:operator value :ref rhs} :warnings warnings}
              :else {:error (str "Cannot read " rhs " after &.")}))
          :else {:error (str "Unexpected text after " q ": " rest* ".")}))

      ;; Name:desc, unquoted sort column
      (re-matches #"[^\s\"'(),&:]+:(asc|desc)" token)
      (let [[c o] (string/split token #":")] {:value {:column c :order (keyword o)}})

      :else (read-scalar token))))

;; ========================================================
;; A directive line
;; ========================================================

(defn parse-line
  "Parses one annotation line. Returns
     {:directive :join :syntax :paren :args [...] :warnings [...]}
   or {:error message :text line} when the line cannot be read. Directive
   names are returned as keywords; pebblestream:div/0 is :div-0."
  [line]
  (let [text (string/trim line)]
    (if-not (string/starts-with? text prefix)
      {:error "Not a directive line." :text line}
      (let [after (subs text (count prefix))
            [_ nm rest*] (re-matches #"([A-Za-z0-9/_-]+)(.*)" after)
            k (when nm (keyword (string/replace (string/lower-case nm) "/" "-")))
            rest* (string/trim (or rest* ""))
            [syntax arg-text bad]
            (cond
              (nil? nm) [nil nil "A directive name must follow pebblestream:."]
              (= "" rest*) [:bare nil]
              (string/starts-with? rest* "(")
              (if (string/ends-with? rest* ")")
                [:paren (subs rest* 1 (dec (count rest*)))]
                [nil nil "The opening parenthesis is not closed."])
              (string/starts-with? rest* ":") [:colon (subs rest* 1)]
              :else [nil nil (str "Unexpected text after the directive name: " rest*)])]
        (if bad
          {:error bad :text line}
          (let [parts (cond
                        (nil? arg-text) []
                        (string/blank? arg-text) []
                        (= :colon syntax) (split-top-level arg-text #{\:})
                        :else (split-top-level arg-text #{\,}))
                read (mapv read-arg parts)
                errors (keep :error read)]
            (if (seq errors)
              {:error (first errors) :text line}
              {:directive k
               :syntax    syntax
               :args      (mapv :value read)
               :warnings  (vec (mapcat :warnings read))})))))))
