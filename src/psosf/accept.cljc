;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.accept
  "Pebble Stream acceptance checks that need no compilation.

   They read formulas and directives as text: a formula is scanned for the
   names of the functions it calls and for references that reach outside the
   spreadsheet; a directive is checked by name. Nothing is parsed into an
   expression tree, translated or evaluated.

   Passing these checks makes an archive acceptable as far as can be judged
   without compiling it. The compiler service has the final say."
  (:require [clojure.string :as string]
            [psosf.directive-names :as names]))

;; ========================================================
;; Formulas
;; ========================================================

(defn scan-formula
  "Scans formula text (beginning with =). Returns
   {:functions #{\"SUM\" ...} :external? bool :dde? bool}.
   String literals (\"...\") and quoted sheet names ('...') are skipped, except
   that a [ inside a quoted sheet name marks an external workbook."
  [formula]
  (let [n (count formula)]
    (loop [i 0 fns #{} external? false dde? false]
      (if (>= i n)
        {:functions fns :external? external? :dde? dde?}
        (let [c (nth formula i)]
          (cond
            (= c \")                                  ; string literal, "" escapes
            (let [j (loop [j (inc i)]
                      (cond (>= j n) j
                            (and (= \" (nth formula j)) (= \" (nth formula (inc j) nil))) (recur (+ j 2))
                            (= \" (nth formula j)) (inc j)
                            :else (recur (inc j))))]
              (recur j fns external? dde?))

            (= c \')                                  ; quoted sheet name, '' escapes
            (let [j (loop [j (inc i)]
                      (cond (>= j n) j
                            (and (= \' (nth formula j)) (= \' (nth formula (inc j) nil))) (recur (+ j 2))
                            (= \' (nth formula j)) (inc j)
                            :else (recur (inc j))))
                  quoted (subs formula i (min j n))]
              (recur j fns (or external? (string/includes? quoted "[")) dde?))

            (= c \[) (recur (inc i) fns true dde?)
            (= c \|) (recur (inc i) fns external? true)

            (re-matches #"[A-Za-z_]" (str c))
            (let [m    (re-find #"^[A-Za-z_][A-Za-z0-9_.]*" (subs formula i))
                  j    (+ i (count m))
                  call (= \( (nth formula j nil))
                  fname (-> m string/upper-case (string/replace #"^_XL(FN|WS)\." ""))]
              (recur j (if call (conj fns fname) fns) external? dde?))

            :else (recur (inc i) fns external? dde?)))))))

(defn- err [code file line column message & [fix]]
  (cond-> {:code code :file file :message message}
    line (assoc :line line) column (assoc :column column) fix (assoc :fix fix)))

(defn formula-errors
  [sheet file allowed]
  (let [headers (mapv :header (:columns sheet))]
    (for [[r row] (map-indexed vector (:rows sheet))
          [c cell] (map-indexed vector row)
          :when (= :formula (:kind cell))
          :let [{:keys [functions external? dde?]} (scan-formula (:text cell))
                line (+ 5 r) column (nth headers c nil)]
          e (concat
             (when external?
               [(err :external-reference file line column
                     "The formula refers to another workbook or a table, which Pebble Stream does not accept.")])
             (when dde?
               [(err :dde file line column "The formula uses DDE (app|topic!item), which reaches another process.")])
             (when (seq allowed)
               (for [f (sort functions) :when (not (contains? allowed f))]
                 (err :function-not-allowed file line column
                      (str f " is not a supported function.")))))]
      e)))

;; ========================================================
;; Directives
;; ========================================================

(defn directive-name
  "The name of a directive line, e.g. \"join\" for pebblestream:join(...)."
  [line]
  (second (re-find #"^\s*pebblestream:([A-Za-z0-9/_-]+)" line)))

(defn- outside-quotes
  "Directive text with double-quoted strings removed."
  [s]
  (string/replace s #"\"(?:[^\"\\]|\\.|\"\")*\"" "\"\""))

(defn directive-findings
  "Errors and warnings for the directives in a worksheet's annotations."
  [sheet file]
  (let [found (for [col (:columns sheet)
                    line (:annotation col)
                    :when (= :directive (:kind line))]
                [col (:text line)])]
    (reduce
     (fn [acc [col text]]
       (let [nm     (directive-name text)
             status (get names/directives nm)
             at     #(err %1 file 1 (:header col) %2)]
         (cond-> acc
           (nil? status)          (update :errors conj (at :unknown-directive (str "pebblestream:" nm " is not a PebbleScript directive.")))
           (= :proposed status)   (update :errors conj (at :directive-not-available (str "pebblestream:" nm " is not yet available.")))
           (= :disabled status)   (update :errors conj (at :directive-not-available (str "pebblestream:" nm " is disabled.")))
           (= :deprecated status) (update :warnings conj (at :deprecated-directive (str "pebblestream:" nm " is deprecated.")))
           (= :reserved status)   (update :warnings conj (at :unverified-directive (str "pebblestream:" nm " cannot be checked by this library.")))
           (re-find #"[\[|]" (outside-quotes text))
           (update :errors conj (at :external-reference "A directive argument refers to another workbook or process.")))))
     {:errors [] :warnings []}
     found)))
