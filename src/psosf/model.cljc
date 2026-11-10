;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.model
  "Building, traversing and editing PSOSF archives as Clojure data.

   Every function is pure: it takes an archive (or a worksheet) and returns a
   new one. Nothing here touches files or JSON; use psosf.core to read, write
   and classify.

   Cell content is {:kind :text :text \"...\"} or {:kind :formula :text \"=...\"}.
   Wherever content is accepted, a plain string is taken as text and
   (formula \"=A1+1\") builds a formula. Values are kept as text, as PSOSF stores
   them.

   The manifest stays a map with string keys, exactly as in manifest.json, so
   an archive always writes back in canonical form.

   (-> (archive \"Account Balances\" {:namespace \"com.example.ledger\"})
       (add-sheet (-> (sheet \"Accounts\")
                      (add-column {:header \"id\"     :type \"text\"})
                      (add-column {:header \"amount\" :type \"number\"
                                   :annotation [\"Balance in US dollars.\"]})
                      (add-column {:header \"negative\" :type \"boolean\"
                                   :annotation [\"pebblestream:fail\"]})
                      (add-row [\"1\" \"435\" (formula \"=B2<0\")]))))"
  (:require [clojure.string :as string]
            [psosf.sheet :as psheet]))

;; ========================================================
;; Content
;; ========================================================

(defn formula
  "Formula content. text must begin with =."
  [text]
  (when-not (string/starts-with? text "=")
    (throw (ex-info "A formula must begin with =" {:text text})))
  {:kind :formula :text text})

(defn text [s] {:kind :text :text (str s)})

(defn formula? [content] (= :formula (:kind content)))

(defn- ->content [x]
  (cond
    (map? x)    x
    (nil? x)    (text "")
    (string? x) (text x)
    :else       (text (str x))))

(defn- ->annotation [lines]
  (mapv (fn [line]
          (if (map? line)
            line
            {:kind (if (string/starts-with? (string/triml line) psheet/directive-prefix) :directive :comment)
             :text line}))
        lines))

;; ========================================================
;; Construction
;; ========================================================

(defn archive
  "A new, empty version 1.1 archive. Options: :namespace, :description,
   :license {:name :url :spdx}."
  ([name] (archive name {}))
  ([name {:keys [namespace description license]}]
   {:version    "1.1"
    :manifest   (cond-> {"format" "psosf" "format-version" "1.1" "name" name "sheets" []}
                  namespace   (assoc "namespace" namespace)
                  description (assoc "description" description)
                  license     (assoc "license" (into {} (for [[k v] license :when v] [(clojure.core/name k) v]))))
    :sheets     []
    :tests      {}
    :signatures nil}))

(defn sheet
  "A new worksheet with no columns."
  [name]
  {:name name :columns [] :rows []})

(defn column
  "A column. :annotation is a list of lines: strings beginning with
   pebblestream: are directives, other strings are comments."
  [{:keys [header type annotation style] :or {type "text"}}]
  {:header header :type type :annotation (->annotation annotation) :style style})

;; ========================================================
;; Traversal
;; ========================================================

(defn sheets [archive] (:sheets archive))

(defn sheet-names [archive] (mapv :name (:sheets archive)))

(defn find-sheet
  "The worksheet with this name, or nil."
  [archive name]
  (first (filter #(= name (:name %)) (:sheets archive))))

(defn headers [sheet] (mapv :header (:columns sheet)))

(defn column-index
  "The zero-based index of the column with this header, or nil."
  [sheet header]
  (first (keep-indexed (fn [i c] (when (= header (:header c)) i)) (:columns sheet))))

(defn find-column [sheet header]
  (some-> (column-index sheet header) (#(nth (:columns sheet) %))))

(defn row-count [sheet] (count (:rows sheet)))

(defn cell
  "Content at a zero-based data row and a header, or nil."
  [sheet row header]
  (when-let [i (column-index sheet header)]
    (get-in sheet [:rows row i])))

(defn rows
  "Rows as maps of header to content."
  [sheet]
  (let [hs (headers sheet)]
    (map #(zipmap hs %) (:rows sheet))))

(defn row-values
  "Rows as maps of header to text: a value's text, or a formula's text."
  [sheet]
  (map #(update-vals % :text) (rows sheet)))

(defn directives
  "Every directive line in the worksheet's annotations:
   ({:column \"amount\" :text \"pebblestream:fail\"} ...)."
  [sheet]
  (for [c (:columns sheet) line (:annotation c) :when (= :directive (:kind line))]
    {:column (:header c) :text (:text line)}))

(defn comments
  "Every comment line in the worksheet's annotations."
  [sheet]
  (for [c (:columns sheet) line (:annotation c) :when (= :comment (:kind line))]
    {:column (:header c) :text (:text line)}))

(defn formulas
  "Every formula cell: ({:row 0 :column \"negative\" :text \"=B2<0\"} ...).
   :row is zero-based over data rows; in Excel it is row (+ row 2)."
  [sheet]
  (let [hs (headers sheet)]
    (for [[r row] (map-indexed vector (:rows sheet))
          [c content] (map-indexed vector row)
          :when (formula? content)]
      {:row r :column (nth hs c) :text (:text content)})))

(defn manifest-get
  "A manifest value by key, e.g. (manifest-get a \"namespace\")."
  [archive k]
  (get-in archive [:manifest k]))

;; ========================================================
;; Editing archives
;; ========================================================

(defn- sync-sheet-list [archive]
  (assoc-in archive [:manifest "sheets"] (sheet-names archive)))

(defn add-sheet
  "Adds a worksheet at the end. Throws if the name is taken."
  [archive sheet]
  (when (find-sheet archive (:name sheet))
    (throw (ex-info (str "A worksheet named " (pr-str (:name sheet)) " already exists") {:name (:name sheet)})))
  (sync-sheet-list (update archive :sheets conj sheet)))

(defn update-sheet
  "Applies f (and args) to the named worksheet."
  [archive name f & args]
  (when-not (find-sheet archive name)
    (throw (ex-info (str "No worksheet named " (pr-str name)) {:name name})))
  (update archive :sheets (fn [ss] (mapv #(if (= name (:name %)) (apply f % args) %) ss))))

(defn remove-sheet [archive name]
  (sync-sheet-list (update archive :sheets (fn [ss] (filterv #(not= name (:name %)) ss)))))

(defn rename-sheet
  "Renames a worksheet. References to it inside formulas and directives are
   not rewritten."
  [archive old-name new-name]
  (when (find-sheet archive new-name)
    (throw (ex-info (str "A worksheet named " (pr-str new-name) " already exists") {:name new-name})))
  (sync-sheet-list (update-sheet archive old-name assoc :name new-name)))

(defn assoc-manifest
  "Sets a manifest value. \"format\", \"format-version\" and \"sheets\" are
   managed by the archive and cannot be set."
  [archive k v]
  (when (#{"format" "format-version" "sheets"} k)
    (throw (ex-info (str "The manifest's " (pr-str k) " is managed by the archive") {:key k})))
  (if (nil? v)
    (update archive :manifest dissoc k)
    (assoc-in archive [:manifest k] v)))

(defn set-license
  "Sets the license: {:name \"Apache License 2.0\" :url \"...\" :spdx \"Apache-2.0\"}."
  [archive license]
  (assoc-manifest archive "license" (into {} (for [[k v] license :when v] [(name k) v]))))

;; ========================================================
;; Editing worksheets
;; ========================================================

(defn add-column
  "Adds a column at the end (or at :index). Existing rows get an empty cell."
  [sheet spec]
  (when (column-index sheet (:header spec))
    (throw (ex-info (str "A column headed " (pr-str (:header spec)) " already exists") {:header (:header spec)})))
  (let [i   (or (:index spec) (count (:columns sheet)))
        ins (fn [v x] (vec (concat (subvec v 0 i) [x] (subvec v i))))]
    (-> sheet
        (update :columns ins (column spec))
        (update :rows (fn [rs] (mapv #(ins % (text "")) rs))))))

(defn remove-column [sheet header]
  (if-let [i (column-index sheet header)]
    (let [del (fn [v] (vec (concat (subvec v 0 i) (subvec v (inc i)))))]
      (-> sheet (update :columns del) (update :rows #(mapv del %))))
    sheet))

(defn update-column
  "Applies f to the column map with this header."
  [sheet header f & args]
  (let [i (or (column-index sheet header)
              (throw (ex-info (str "No column headed " (pr-str header)) {:header header})))]
    (update-in sheet [:columns i] #(apply f % args))))

(defn set-annotation
  "Replaces a column's annotation with these lines (strings or line maps)."
  [sheet header lines]
  (update-column sheet header assoc :annotation (->annotation lines)))

(defn add-annotation-line
  "Appends one line (a directive or a comment) to a column's annotation."
  [sheet header line]
  (update-column sheet header update :annotation into (->annotation [line])))

(defn set-type [sheet header type] (update-column sheet header assoc :type type))

(defn set-style [sheet header style] (update-column sheet header assoc :style style))

(defn add-row
  "Appends a row: a vector of values in column order, or a map of header to
   value. Missing values are empty."
  [sheet values]
  (let [hs  (headers sheet)
        row (if (map? values)
              (mapv #(->content (get values %)) hs)
              (let [vs (vec values)]
                (when (> (count vs) (count hs))
                  (throw (ex-info "More values than columns" {:values values :headers hs})))
                (mapv #(->content (get vs % nil)) (range (count hs)))))]
    (update sheet :rows conj row)))

(defn set-cell
  "Sets the content at a zero-based data row and a header."
  [sheet row header value]
  (let [i (or (column-index sheet header)
              (throw (ex-info (str "No column headed " (pr-str header)) {:header header})))]
    (assoc-in sheet [:rows row i] (->content value))))

(defn remove-row [sheet row]
  (update sheet :rows #(vec (concat (subvec % 0 row) (subvec % (inc row))))))
