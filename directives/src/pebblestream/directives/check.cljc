;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.check
  "Checks a PSOSF archive against everything Pebble Stream can verify without
   compiling it, and reports the results in one place, for people, pipelines
   and AI agents:

     1. structure and acceptance, from psosf.core (escaping, manifest,
        function allow-list, references outside the spreadsheet);
     2. every directive line: that it parses, names a known directive, and
        matches that directive's signature;
     3. the worksheet rules from the PebbleScript Reference: repetition,
        exclusions, requirements, header arguments, formulas in transformed
        columns, cycles, ignore, allowed-values.

   (check-archive archive)  -> Clojure data
   (check-entries entries)  -> JSON-shaped report (string keys)
   (check-json text)        -> the same report, as JSON text

   Every finding names the worksheet, and where it applies the column and
   directive, with a fix when one is known."
  (:require [clojure.string :as string]
            [pebblestream.directives.parse :as parse]
            [pebblestream.directives.schema :as schema]
            [psosf.core :as psosf]
            [psosf.json :as json]))

;; ========================================================
;; Formula references
;; ========================================================

(defn formula-sheet-references
  "The worksheets a formula refers to, by name: 'Sheet Name'!A1 or Sheet!A1.
   Text inside string literals is ignored."
  [formula]
  (let [n (count formula)]
    (loop [i 0 refs #{}]
      (if (>= i n)
        refs
        (let [c (nth formula i)]
          (cond
            (= c \")
            (recur (loop [j (inc i)]
                     (cond (>= j n) j
                           (and (= \" (nth formula j)) (= \" (nth formula (inc j) nil))) (recur (+ j 2))
                           (= \" (nth formula j)) (inc j)
                           :else (recur (inc j))))
                   refs)

            (= c \')
            (let [j (loop [j (inc i)]
                      (cond (>= j n) j
                            (and (= \' (nth formula j)) (= \' (nth formula (inc j) nil))) (recur (+ j 2))
                            (= \' (nth formula j)) (inc j)
                            :else (recur (inc j))))]
              (recur j (if (= \! (nth formula j nil))
                         (conj refs (string/replace (subs formula (inc i) (dec j)) "''" "'"))
                         refs)))

            (re-matches #"[A-Za-z_]" (str c))
            (let [m (re-find #"^[A-Za-z_][A-Za-z0-9_.]*" (subs formula i))
                  j (+ i (count m))]
              (recur j (if (= \! (nth formula j nil)) (conj refs m) refs)))

            :else (recur (inc i) refs)))))))

;; ========================================================
;; Archive -> worksheet model
;; ========================================================

(defn- finding [m] (into {} (remove (comp nil? val) m)))

(defn worksheet-model
  "Builds the input of schema/spreadsheet-errors from a psosf.core archive.
   Returns {:model {...} :errors [...] :warnings [...]} where errors and
   warnings come from reading the directive lines."
  [archive]
  (let [results
        (for [{:keys [name columns rows]} (:sheets archive)]
          (let [parsed (for [{:keys [header annotation]} columns
                             {:keys [kind text]} annotation
                             :when (= :directive kind)]
                         (assoc (parse/parse-line text) :column header :sheet name :text text))
                formulas (into {}
                               (for [[i {:keys [header]}] (map-indexed vector columns)
                                     :let [fs (keep #(let [c (nth % i nil)] (when (= :formula (:kind c)) (:text c))) rows)]
                                     :when (seq fs)]
                                 [header (disj (reduce into #{} (map formula-sheet-references fs)) name)]))]
            {:sheet {:name name
                     :headers (mapv :header columns)
                     :directives (vec (for [p parsed :when (not (:error p))]
                                        (select-keys p [:directive :syntax :args :column])))
                     :formulas formulas}
             :parsed parsed}))]
    {:model {:sheets (mapv :sheet results)}
     :parsed (mapcat :parsed results)}))

;; ========================================================
;; Directive lines
;; ========================================================

(defn- directive-findings
  "Parse errors, unknown directives, signature mismatches, and warnings for
   deprecated directives, the deprecated colon form and ambiguous backslashes."
  [parsed]
  (reduce
   (fn [acc {:keys [sheet column text error directive syntax args warnings]}]
     (let [at (fn [code message & [fix]]
                (finding {:code code :sheet sheet :column column :directive text :message message :fix fix}))]
       (cond
         error
         (update acc :errors conj (at :unreadable-directive error))

         (not (contains? schema/all-directives directive))
         (update acc :errors conj (at :unknown-directive (str "pebblestream:" (name directive) " is not a PebbleScript directive.")))

         :else
         (let [entry (get schema/all-directives directive)
               problem (schema/explain {:directive directive :syntax syntax :args args})]
           (cond-> acc
             (= :proposed (:status entry))
             (update :errors conj (at :directive-not-available (str "pebblestream:" (name directive) " is not yet available.")))
             (= :disabled (:status entry))
             (update :errors conj (at :directive-not-available (str "pebblestream:" (name directive) " is disabled.")))
             problem
             (update :errors conj (at :bad-arguments (str "The arguments do not match pebblestream:" (name directive)
                                                          "'s signature: " (pr-str problem))))
             (= :colon syntax)
             (update :warnings conj (at :deprecated-syntax "The colon form is deprecated."
                                        "Write the arguments in parentheses, e.g. pebblestream:name(\"a\", \"b\")."))
             (seq warnings)
             (update :warnings into (for [w warnings] (at (:code w) (:message w)))))))))
   {:errors [] :warnings []}
   parsed))

;; ========================================================
;; The check
;; ========================================================

(defn check-archive
  "Every finding for a psosf.core archive that has already been classified as
   valid or acceptable: {:errors [...] :warnings [...]}."
  [archive]
  (let [{:keys [model parsed]} (worksheet-model archive)
        d (directive-findings parsed)
        r (schema/spreadsheet-errors model)]
    {:errors   (vec (concat (:errors d) (map finding (:errors r))))
     :warnings (vec (concat (:warnings d) (map finding (:warnings r))))}))

(defn- ->json [findings]
  (mapv (fn [f] (into {} (for [[k v] f] [(name k) (if (keyword? v) (name v) v)]))) findings))

(def ^:private superseded
  "Directive findings from psosf.core's acceptance check. The full check
   reports the same problems itself, with arguments, signatures and
   replacements, so these are dropped to avoid duplicates."
  #{:unknown-directive :directive-not-available :deprecated-directive :unverified-directive})

(defn- drop-superseded [findings] (remove #(contains? superseded (:code %)) findings))

(defn check-entries
  "Checks an archive given as {path text}. Returns a JSON-shaped report:
     {\"passed\"    true when the archive is well-formed, breaks no rule, and
                   has no acceptance errors other than a missing allow-list,
      \"level\"     the psosf.core level: invalid, valid or acceptable,
      \"errors\"    structural errors, then directive and worksheet-rule errors,
      \"warnings\"  [...],
      \"acceptance-errors\" why a valid archive is not acceptable}
   Options: \"allowed-functions\"."
  ([entries] (check-entries entries {}))
  ([entries options]
   (let [allowed (get options "allowed-functions")
         c (-> (psosf/classify entries (cond-> {} allowed (assoc :allowed-functions (set (map string/upper-case allowed)))))
               (update :warnings drop-superseded)
               (update :acceptance-errors drop-superseded))
         rules (when-not (= :invalid (:level c)) (check-archive (:archive c)))
         errors (concat (:errors c) (:errors rules))]
     {"passed"            (and (not= :invalid (:level c))
                               (empty? errors)
                               ;; a missing allow-list is a configuration gap, not a fault in the archive
                               (every? #(= :allow-list-not-configured (:code %)) (:acceptance-errors c)))
      "level"             (name (:level c))
      "errors"            (->json errors)
      "warnings"          (->json (concat (:warnings c) (:warnings rules)))
      "acceptance-errors" (->json (:acceptance-errors c))})))

(defn check-json
  "{\"entries\": {...}, \"options\": {...}} -> report, as JSON text."
  [text]
  (let [{:strs [entries options]} (json/read-str text)]
    (json/write-compact (check-entries entries (or options {})))))
