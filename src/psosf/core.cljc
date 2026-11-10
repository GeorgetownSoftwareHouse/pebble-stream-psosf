;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.core
  "Reading, writing and classifying PSOSF archives.

   An archive is held as data:
     {:version    \"1.1\"                     ; or \"1.0\" for an archive without a manifest
      :manifest   {\"format\" \"psosf\" ...}   ; JSON object, string keys
      :sheets     [sheet ...]                ; in manifest order, see psosf.sheet
      :tests      {\"zero-balance\" {:json {...} :inputs {\"Accounts\" csv-text}
                                    :expected {\"Account Totals\" csv-text}}}
      :signatures text-or-nil}

   classify reports one of three levels:
     :invalid     not a well-formed PSOSF archive
     :valid       well-formed: safe to open and convertible to Excel
     :acceptable  also passes every Pebble Stream acceptance check that can be
                  made without compiling (function allow-list, references that
                  reach outside the spreadsheet, directive names). The compiler
                  service has the final say."
  (:require [clojure.string :as string]
            [psosf.accept :as accept]
            [psosf.allow-list :as allow-list]
            [psosf.archive :as archive]
            [psosf.csv :as csv]
            [psosf.field :as field]
            [psosf.json :as json]
            [psosf.manifest :as manifest]
            [psosf.sheet :as sheet]))

(defn- err [code file message] (cond-> {:code code :message message} file (assoc :file file)))

(defn- parse-json [path text]
  (try {:value (json/read-str text)}
       (catch #?(:clj Exception :cljs :default) e
         {:error (err :bad-json path (str path " is not valid JSON: " (ex-message e)))})))

(def ^:private test-keys #{"description" "expect" "message" "ordered" "tolerance"})

(defn- test-csv-errors [path text]
  (let [records (try (csv/read-records text) (catch #?(:clj Exception :cljs :default) e {:bad (ex-message e)}))]
    (if (map? records)
      [(err :bad-csv path (str "The file is not valid CSV: " (:bad records)))]
      (let [width (count (first records))]
        (concat
         (keep-indexed (fn [i r] (when (not= width (count r))
                                   (assoc (err :field-count path (str "Line " (inc i) " has " (count r) " fields; the header has " width ".")) :line (inc i))))
                       records)
         (for [[i r] (map-indexed vector records) f r
               :let [res (field/read-field f {:cell? false})]
               :when (:error res)]
           (cond-> (assoc (err (if (= :formula-outside-cells (:error res)) :formula-in-test (:error res)) path
                               (if (= :formula-outside-cells (:error res)) "Test files may not contain formulas." (:message res)))
                          :line (inc i))
             (:fix res) (assoc :fix (:fix res)))))))))

(defn read-entries
  "Reads an archive from an entries map {path text}. Returns
   {:archive archive :errors [...] :warnings [...]}."
  [entries]
  (let [paths       (vec (keys entries))
        struct-errs (vec (archive/structure-errors paths))
        has-manifest (contains? entries "manifest.json")
        version     (if has-manifest "1.1" "1.0")
        {m :value m-err :error} (when has-manifest (parse-json "manifest.json" (get entries "manifest.json")))
        {merrs :errors mwarns :warnings} (if (and has-manifest (not m-err)) (manifest/check m) {:errors [] :warnings []})
        csv-files   (sort (filter #(re-matches #"[^/]+\.csv" %) paths))
        file-sheets (map #(string/replace % #"\.csv$" "") csv-files)
        listed      (if (and has-manifest (vector? (get m "sheets"))) (get m "sheets") (vec file-sheets))
        sheet-list-errs
        (when has-manifest
          (concat
           (for [s listed :when (not (contains? entries (str s ".csv")))]
             (err :missing-sheet-file "manifest.json" (str "\"" s "\" is listed in sheets but has no file " s ".csv.")))
           (for [s file-sheets :when (not (some #{s} listed))]
             (err :unlisted-sheet (str s ".csv") (str s ".csv is not listed in the manifest's sheets.")))))
        parsed      (for [s listed :let [p (str s ".csv")] :when (contains? entries p)]
                      (sheet/parse p (get entries p) {:legacy? (= "1.0" version)}))
        test-names  (distinct (keep #(second (re-find #"^tests/([^/]+)/" %)) paths))
        tests       (into {}
                          (for [t test-names]
                            (let [prefix (str "tests/" t "/")
                                  files  (filter #(string/starts-with? % prefix) paths)
                                  pick   (fn [dir] (into {} (for [p files :let [m (re-find (re-pattern (str "^" prefix dir "/(.+)\\.csv$")) p)] :when m]
                                                          [(second m) (get entries p)])))
                                  tj     (get entries (str prefix "test.json"))]
                              [t {:json (when tj (:value (parse-json (str prefix "test.json") tj)))
                                  :inputs (pick "inputs") :expected (pick "expected")}])))
        test-errs   (concat
                     (for [t test-names
                           :let [p (str "tests/" t "/test.json") tj (get entries p)]
                           e (cond
                               (nil? tj) [(err :missing-test-json p "Every test needs a test.json.")]
                               :else (let [{v :value e :error} (parse-json p tj)]
                                       (cond e [e]
                                             (not (map? v)) [(err :bad-test p "test.json must be a JSON object.")]
                                             :else (concat
                                                    (when-not (#{"pass" "warn" "fail"} (get v "expect"))
                                                      [(err :bad-test p "\"expect\" must be pass, warn or fail.")])
                                                    (for [k (keys v) :when (not (test-keys k))]
                                                      (err :bad-test p (str "Unknown test.json key \"" k "\".")))))))]
                       e)
                     (for [[t {:keys [inputs expected]}] tests
                           [dir files] [["inputs" inputs] ["expected" expected]]
                           [s _] files :when (not (some #{s} listed))]
                       (err :unknown-test-sheet (str "tests/" t "/" dir "/" s ".csv") (str "\"" s "\" is not a worksheet in this archive.")))
                     (for [p paths :when (re-matches #"tests/[^/]+/(inputs|expected)/[^/]+\.csv" p)
                           e (test-csv-errors p (get entries p))]
                       e))
        canon-warns (concat
                     (when (and has-manifest m (not m-err) (not (manifest/canonical? (get entries "manifest.json") m)))
                       [(err :not-canonical "manifest.json" "manifest.json is not in canonical form; signatures and round trips depend on it.")])
                     (when (= "1.0" version)
                       [(err :version-1-0 nil "No manifest.json: read as a version 1.0 archive.")])
                     (when (contains? entries "signatures.json")
                       [(err :signatures-not-verified "signatures.json" "This version of the library does not verify signatures.")]))]
    {:archive  {:version    version
                :manifest   m
                :sheets     (vec (keep :sheet parsed))
                :tests      tests
                :signatures (get entries "signatures.json")}
     :errors   (vec (concat struct-errs (when m-err [m-err]) merrs sheet-list-errs (mapcat :errors parsed) test-errs))
     :warnings (vec (concat mwarns canon-warns))}))

(defn acceptance
  "Pebble Stream acceptance findings for a structurally valid archive."
  [{:keys [sheets]} {:keys [allowed-functions] :or {allowed-functions allow-list/functions}}]
  (let [per-sheet (for [s sheets :let [file (str (:name s) ".csv")]]
                    (let [d (accept/directive-findings s file)]
                      {:errors (concat (accept/formula-errors s file allowed-functions) (:errors d))
                       :warnings (:warnings d)}))]
    {:errors   (vec (concat (when (empty? allowed-functions)
                              [(err :allow-list-not-configured nil
                                    "No function allow-list is configured, so formulas cannot be checked.")])
                            (mapcat :errors per-sheet)))
     :warnings (vec (mapcat :warnings per-sheet))}))

(defn classify
  "Classifies an archive given as an entries map {path text}. Returns
   {:level :invalid|:valid|:acceptable :archive ... :errors [...]
    :warnings [...] :acceptance-errors [...]}. :acceptance-errors explain why a
   valid archive is not acceptable. Options: :allowed-functions."
  ([entries] (classify entries {}))
  ([entries opts]
   (let [{:keys [archive errors warnings]} (read-entries entries)]
     (if (seq errors)
       {:level :invalid :archive archive :errors errors :warnings warnings :acceptance-errors []}
       (let [acc (acceptance archive opts)]
         {:level             (if (seq (:errors acc)) :valid :acceptable)
          :archive           archive
          :errors            []
          :warnings          (into warnings (:warnings acc))
          :acceptance-errors (:errors acc)})))))

(defn write-entries
  "Writes an archive to an entries map {path text}, in canonical form and as
   version 1.1. The manifest's sheets are taken from :sheets, in order."
  [{:keys [manifest sheets tests signatures]}]
  (let [m (assoc (or manifest {}) "format" "psosf" "format-version" "1.1"
                 "sheets" (mapv :name sheets))]
    (cond-> (into {"manifest.json" (json/write-canonical m)}
                  (for [s sheets] [(str (:name s) ".csv") (sheet/write s)]))
      (seq tests)
      (into (for [[t {:keys [json inputs expected]}] tests
                  [p text] (concat
                            (when json [[(str "tests/" t "/test.json") (json/write-canonical json)]])
                            (for [[s csv] inputs] [(str "tests/" t "/inputs/" s ".csv") csv])
                            (for [[s csv] expected] [(str "tests/" t "/expected/" s ".csv") csv]))]
              [p text]))
      signatures (assoc "signatures.json" signatures))))

#?(:clj
   (defn classify-file
     "Reads and classifies a .psosf file."
     ([path] (classify-file path {}))
     ([path opts]
      (let [{:keys [entries errors]} (archive/read-zip path)]
        (if (seq errors)
          {:level :invalid :errors errors :warnings [] :acceptance-errors []}
          (classify entries opts))))))

#?(:clj
   (defn write-file
     "Writes an archive to a .psosf file."
     [path archive]
     (archive/write-zip path (write-entries archive))))
