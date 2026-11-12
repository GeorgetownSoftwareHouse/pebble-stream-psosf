;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.text
  "Reads and writes one line of textual PebbleScript.

   `parse` turns a directive line into the parsed form the registry validates,

     {:directive :join, :syntax :paren, :args [\"Rates\" \"id\" \"rate-id\"]}

   and `render` writes a parsed directive back as canonical text.

   Arguments in the parsed form:

     \"Accounts\"                      a string (quoted, or an unquoted name)
     12, 0.5                          a number (unquoted)
     {:ref \"Inputs!B2\"}               a cell reference (unquoted)
     {:operator \"<=\" :column \"Balance\"} a column predicate, \"<=\"&\"Balance\"
     {:column \"Date\" :order :desc}     a sort column with a suffix, \"Date\":desc

   Accepted forms:

     pebblestream:unique                 bare
     pebblestream:from(\"Accounts\")       parentheses
     pebblestream:allowed-values:a,b     colon (deprecated); arguments are
                                         separated by commas or colons

   A cell reference always names its worksheet (Inputs!B2). An unqualified
   address such as ID5 or Q1 is read as a name, so headers that look like cell
   addresses are never mistaken for references."
  (:require [clojure.string :as string]
            [pebblestream.parse-str :as ps]))

;; ========================================================
;; Lexical rules
;; ========================================================

(def ^:private prefix "pebblestream:")

(def cell-ref-re
  "One cell in A1 notation, qualified by its worksheet name and optionally
   absolute. The same pattern as :ps/cell-ref in the registry."
  #"^(?:'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!\$?[A-Za-z]{1,3}\$?[0-9]+$")

(def ^:private number-re #"^-?[0-9]+(?:\.[0-9]+)?$")

(def operators #{"=" "<>" "<" "<=" ">" ">="})

(defn- error [code message & {:as more}]
  (merge {:code code :message message} more))

(defn directive-line?
  "True when a line of an annotation is PebbleScript: its first non-whitespace
   characters are pebblestream:."
  [line]
  (string/starts-with? (string/triml line) prefix))

;; ========================================================
;; Splitting arguments
;; ========================================================

(defn- quoted-end
  "Index of the quote closing the double-quoted string that opens at i, or nil
   when it is not closed. Doubled quotes and backslash escapes stay inside."
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (when (< j n)
        (let [c (nth s j)]
          (cond
            (= \\ c)                                  (recur (+ j 2))
            (and (= \" c) (< (inc j) n)
                 (= \" (nth s (inc j))))              (recur (+ j 2))
            (= \" c)                                  j
            :else                                     (recur (inc j))))))))

(defn- single-quoted-end
  "Index of the quote closing a single-quoted sheet name that opens at i."
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (when (< j n)
        (let [c (nth s j)]
          (cond
            (and (= \' c) (< (inc j) n) (= \' (nth s (inc j)))) (recur (+ j 2))
            (= \' c)                                           j
            :else                                              (recur (inc j))))))))

(defn- split-args
  "Splits argument text at separators outside quotes. Returns
   {:args [raw ...]} with each raw argument trimmed, or {:error ...}."
  [s separators]
  (let [n (count s)]
    (loop [i 0 start 0 out []]
      (if (>= i n)
        (let [last-arg (string/trim (subs s start))]
          (if (and (string/blank? last-arg) (empty? out))
            {:args []}
            {:args (conj out last-arg)}))
        (let [c (nth s i)]
          (cond
            (= \" c)
            (if-let [e (quoted-end s i)]
              (recur (inc e) start out)
              {:error (error :unterminated-string "A quoted argument is not closed.")})

            ;; A single quote opens a sheet name only at the start of an argument,
            ;; as in 'Run Settings'!B1; elsewhere it is an ordinary character.
            (and (= \' c) (string/blank? (subs s start i)))
            (if-let [e (single-quoted-end s i)]
              (recur (inc e) start out)
              {:error (error :unterminated-sheet-name "A single-quoted sheet name is not closed.")})

            (contains? separators c)
            (recur (inc i) (inc i) (conj out (string/trim (subs s start i))))

            :else
            (recur (inc i) start out)))))))

(defn- top-level-index
  "Index of the first c outside double quotes, or nil."
  [s c]
  (let [n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [x (nth s i)]
          (cond
            (= \" x) (when-let [e (quoted-end s i)] (recur (inc e)))
            (= c x)  i
            :else    (recur (inc i))))))))

;; ========================================================
;; Reading one argument
;; ========================================================

(defn- read-quoted
  "Reads a double-quoted string. Returns {:value v :warnings [...]} or {:error ...}."
  [raw]
  (if-let [{:keys [value warnings]} (ps/parse-str* raw)]
    {:value value :warnings warnings}
    {:error (error :invalid-string (str "Not a valid quoted string: " raw))}))

(defn- read-number [raw]
  (if (string/includes? raw ".")
    (parse-double raw)
    (or (parse-long raw) (parse-double raw))))

(defn- read-unquoted
  "Reads an unquoted argument: a number, a cell reference or a name."
  [raw allow-spaces?]
  (cond
    (re-matches number-re raw)   {:value (read-number raw)}
    (re-matches cell-ref-re raw) {:value {:ref raw}}
    (and (not allow-spaces?)
         (re-find #"\s" raw))    {:error (error :unquoted-space
                                                (str "An unquoted argument may not contain a space: " raw
                                                     ". Enclose it in double quotes."))}
    :else                        {:value raw}))

(defn- read-name
  "Reads a quoted or unquoted name (never a number or reference)."
  [raw]
  (cond
    (string/starts-with? raw "\"") (read-quoted raw)
    (string/blank? raw)            {:error (error :missing-name "A name is missing.")}
    (re-find #"\s" raw)            {:error (error :unquoted-space (str "An unquoted name may not contain a space: " raw))}
    :else                          {:value raw}))

(defn- read-predicate
  "Reads \"<=\"&\"Balance\": a quoted operator joined to a header with &."
  [raw amp]
  (let [left  (string/trim (subs raw 0 amp))
        right (string/trim (subs raw (inc amp)))
        op    (when (string/starts-with? left "\"") (read-quoted left))
        col   (read-name right)]
    (cond
      (or (nil? op) (:error op))
      {:error (error :invalid-predicate (str "The operator before & must be quoted: " raw))}

      (not (contains? operators (:value op)))
      {:error (error :invalid-predicate
                     (str "Unknown operator " (pr-str (:value op)) " in " raw
                          ". Use one of = <> < <= > >=."))}

      (:error col)
      {:error (error :invalid-predicate (str "The header after & is missing or invalid in " raw
                                              ": " (:message (:error col))))}

      :else
      {:value    {:operator (:value op) :column (:value col)}
       :warnings (into (:warnings op []) (:warnings col []))})))

(defn- read-arg
  "Reads one raw argument into its parsed value."
  [raw colon-form?]
  (cond
    (string/blank? raw)
    {:error (error :empty-argument "An argument is empty (two separators in a row, or a trailing one).")}

    ;; A reference is recognized first: a quoted sheet name in it may contain
    ;; & or a double quote, which must not be read as a predicate or a string.
    (re-matches cell-ref-re raw)
    {:value {:ref raw}}

    (and (not colon-form?)
         (some->> (re-matches #"^(.+):(asc|desc)$" raw) second (re-matches cell-ref-re)))
    (let [[_ column order] (re-matches #"^(.+):(asc|desc)$" raw)]
      {:value {:column {:ref column} :order (keyword order)}})

    (top-level-index raw \&)
    (read-predicate raw (top-level-index raw \&))

    (string/starts-with? raw "\"")
    (let [e (quoted-end raw 0)]
      (cond
        (nil? e)                    {:error (error :unterminated-string "A quoted argument is not closed.")}
        (= e (dec (count raw)))     (read-quoted raw)
        :else
        (let [rest-text (subs raw (inc e))]
          (if-let [[_ order] (re-matches #"^:(asc|desc)$" rest-text)]
            (let [q (read-quoted (subs raw 0 (inc e)))]
              (if (:error q)
                q
                {:value {:column (:value q) :order (keyword order)} :warnings (:warnings q)}))
            {:error (error :unexpected-text
                           (str "Unexpected text after a quoted argument: " rest-text))}))))

    ;; An unquoted sort column with a suffix: a name, or a reference whose
    ;; quoted sheet name may contain spaces ('Run Settings'!B1:desc).
    (and (not colon-form?) (re-matches #"^(.+):(asc|desc)$" raw))
    (let [[_ column order] (re-matches #"^(.+):(asc|desc)$" raw)
          {:keys [value error]} (read-unquoted column false)]
      (if error {:error error} {:value {:column value :order (keyword order)}}))

    :else
    (read-unquoted raw colon-form?)))

;; ========================================================
;; Parsing a line
;; ========================================================

(defn- directive-keyword [name]
  (keyword (string/replace name "/" "-")))   ; div/0 is keyed :div-0

(defn parse
  "Parses one directive line. Returns
     {:directive {:directive k :syntax s :args [...]} :warnings [...]}
   or {:errors [...] :warnings [...]}.

   This reads the line's structure only. Whether the directive exists and its
   arguments fit its signature is checked by the registry
   (pebblestream.directives.schema/explain)."
  [line]
  (let [s (string/trim line)]
    (if-not (string/starts-with? s prefix)
      {:errors [(error :not-a-directive "A directive line begins with pebblestream:.")] :warnings []}
      (let [body (subs s (count prefix))
            [_ name rest-text] (re-matches #"^([a-z][a-z0-9-]*(?:/0)?)(.*)$" body)]
        (if-not name
          {:errors [(error :invalid-name (str "Not a directive name: " body))] :warnings []}
          (let [k (directive-keyword name)
                [syntax arg-text]
                (cond
                  (= "" rest-text)                      [:bare nil]
                  (and (string/starts-with? rest-text "(")
                       (string/ends-with? rest-text ")")) [:paren (subs rest-text 1 (dec (count rest-text)))]
                  (string/starts-with? rest-text ":")   [:colon (subs rest-text 1)]
                  :else                                 [nil nil])]
            (if-not syntax
              {:errors [(error :invalid-syntax
                               (str "Expected ( or : or the end of the line after " prefix name
                                    ", found: " rest-text))]
               :warnings []}
              (let [split (if (= :bare syntax)
                            {:args []}
                            (split-args arg-text (if (= :colon syntax) #{\, \:} #{\,})))]
                (if (:error split)
                  {:errors [(:error split)] :warnings []}
                  (let [results  (mapv #(read-arg % (= :colon syntax)) (:args split))
                        errors   (keep :error results)
                        warnings (vec (mapcat :warnings results))]
                    (if (seq errors)
                      {:errors (vec errors) :warnings warnings}
                      {:directive {:directive k :syntax syntax :args (mapv :value results)}
                       :warnings  warnings})))))))))))

;; ========================================================
;; Rendering
;; ========================================================

(defn- render-number [x]
  #?(:clj  (if (and (float? x) (== x (Math/rint x)) (< (Math/abs (double x)) 1e15))
             (str (long x))
             (if (float? x) (.toPlainString (bigdec x)) (str x)))
     :cljs (str x)))

(defn- render-column [c]
  (if (map? c) (:ref c) (ps/quote-str c)))

(defn render-arg
  "Writes one parsed argument in canonical text."
  [x]
  (cond
    (string? x)                     (ps/quote-str x)
    (number? x)                     (render-number x)
    (contains? x :ref)              (:ref x)
    (contains? x :operator)         (str (ps/quote-str (:operator x)) "&" (render-column (:column x)))
    (contains? x :order)            (str (render-column (:column x)) ":" (name (:order x)))
    :else (throw (ex-info "Cannot render argument" {:arg x}))))

(defn render
  "Writes a parsed directive as canonical text: parentheses, or the bare form
   when it has no arguments. The colon form is never produced."
  [{:keys [directive args]}]
  (let [name (if (= :div-0 directive) "div/0" (clojure.core/name directive))]
    (if (empty? args)
      (str prefix name)
      (str prefix name "(" (string/join ", " (map render-arg args)) ")"))))
