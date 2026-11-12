;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.notation
  "PebbleScript JSON Notation: a named-argument JSON form of every directive,
   converted one-to-one to and from textual PebbleScript.

   JSON values here are JSON-shaped Clojure data: maps with string keys,
   vectors, strings and numbers, as any JSON reader produces.

     (text->json \"pebblestream:join(\\\"Rates\\\", \\\"id\\\", \\\"rate-id\\\")\")
     ;=> {:value {\"directive\" \"join\" \"sheet\" \"Rates\"
     ;            \"on\" [{\"column\" \"id\" \"source-column\" \"rate-id\"}]}
     ;    :errors [] :warnings []}

     (json->text {\"directive\" \"group\" \"columns\" [\"fund\"]})
     ;=> {:value \"pebblestream:group(\\\"fund\\\")\" :errors [] :warnings []}

   Every result is {:value v :errors [...] :warnings [...]}; :value is absent
   when there are errors. Both directions check the directive against the
   registry (pebblestream.directives.schema), so a value is returned only for
   a directive whose arguments fit its signature.

   `annotation->json` and `json->annotation` convert a whole header note:
   directive lines and comment lines, in order."
  (:require [clojure.string :as string]
            [pebblestream.directives.schema :as schema]
            [pebblestream.directives.text :as text]))

;; ========================================================
;; Key mapping
;; ========================================================
;;
;; Each directive's JSON keys, and how they map to its positional arguments.
;;
;;   :fixed     required keys, then optional keys, one argument each, in order
;;   :many      one key holding every argument as an array
;;   :pairs     leading fixed keys, then one key holding an array of objects,
;;              each object taking two consecutive arguments
;;   :conditions, :sort-columns, :sorted
;;              filter/prune, sort and sorted, whose shapes are their own

(defn- fixed
  ([required] (fixed required []))
  ([required optional] {:shape :fixed :required required :optional optional}))

(defn- many [key & {:keys [optional]}]
  {:shape :many :key key :optional (boolean optional)})

(defn- pairs [prefix key fields & {:keys [optional]}]
  {:shape :pairs :prefix prefix :key key :fields fields :optional (boolean optional)})

(def ^:private none (fixed []))

(def ^:private no-arg-directives
  [:unique :grouped :warn :fail :not-blank :ignore :do-not-compile :out
   :text :number :boolean :date
   :coalesce :expand
   :workflow-each :workflow-group :workflow-all :workflow-filter
   :block-id :template-row :template-column :leftmost-column :rightmost-column
   :top-row :bottom-row :block-row-count :row-oriented-headers :column-oriented-headers
   :template-out-header :template-out-cell :template-out-down :template-out-right
   :template-out-group :template-out-value-type :template-out-generate-clean])

(def ^:private aggregate (fixed ["column"] ["new-column"]))

(def notation
  "Directive keyword -> its JSON Notation key mapping."
  (merge
   (zipmap no-arg-directives (repeat none))
   {:from              (many "sheets")
    :cartesian-product (many "sheets")
    :join              (pairs ["sheet"] "on" ["column" "source-column"] :optional true)
    :outer-join        (pairs ["sheet"] "on" ["column" "source-column"] :optional true)
    :filter            {:shape :conditions}
    :prune             {:shape :conditions}
    :sort              {:shape :sort-columns}
    :header-map        (pairs [] "mappings" ["column" "new-column"])
    :group             (many "columns")
    :group-unique      (many "columns")
    :group-sum         (many "columns")
    :group-ratio       (many "columns")
    :sum aggregate :min aggregate :max aggregate :average aggregate
    :mult aggregate :equal aggregate :ratio aggregate
    :balance           (fixed ["column" "new-column-1" "new-column-2"])
    :yield             (fixed ["column-1" "column-2" "column-3" "column-4" "new-column"])
    :npv               (fixed ["column-1" "column-2" "column-3" "column-4" "new-column"])
    :pivot             (fixed ["column" "value" "pivot-column" "filter-type"] ["filter-value"])
    :apportion         (fixed ["column-1" "column-2" "column-3" "column-4"])
    :relieve           (fixed ["column-1" "column-2" "column-3" "column-4"])
    :paths             (fixed ["column-1" "column-2" "column-3"])
    :paths-operation   (fixed ["operation" "new-column"])
    :match-rows        (fixed ["sheet"])
    :generate-rows     (fixed ["count"])
    :stop              (fixed [] ["max-rows"])
    :sorted            {:shape :sorted}
    :allowed-values    (many "values")
    :hint              (fixed ["key"] ["value"])
    :identical-columns (many "sheets")
    :id                (fixed [] ["group"])
    :workflow-iterate  (fixed ["iterations"])
    :workflow-terminate (fixed ["max-iterations"])
    :template-name     (fixed ["name"])
    :template-out-style (many "styles" :optional true)}))

(def without-notation
  "Directives in the registry that have no JSON Notation, with the reason."
  {:regex        "Disabled."
   :split        "Deprecated; has no JSON Notation."
   :div-0        "Deprecated; has no JSON Notation."
   :header-count "Deprecated; has no JSON Notation."})

(defn- pending-reason [k]
  (cond
    (string/starts-with? (name k) "board-")
    "Board directives will be added once their signatures are confirmed."
    (= :proposed (get-in schema/all-directives [k :status]))
    "Connector directives will be added once their signatures are confirmed."))

(defn no-notation-reason
  "Why a registry directive has no JSON Notation, or nil when it has one."
  [k]
  (when-not (contains? notation k)
    (or (get without-notation k) (pending-reason k))))

(defn- allowed-keys [{:keys [shape required optional key prefix]}]
  (set (cons "directive"
             (case shape
               :fixed        (concat required optional)
               :many         [key]
               :pairs        (conj prefix key)
               :conditions   ["conditions"]
               :sort-columns ["columns"]
               :sorted       ["order" "priority"]))))

;; ========================================================
;; Results and errors
;; ========================================================

(defn- error [code message & {:as more}]
  (merge {:code code :message message} more))

(defn- failure [errors warnings]
  {:errors (vec errors) :warnings (vec warnings)})

(defn- success [value warnings]
  {:value value :errors [] :warnings (vec warnings)})

(defn- registry-warnings [parsed]
  (cond-> []
    (schema/deprecated-directive? parsed)
    (conj (error :deprecated-directive
                 (str "pebblestream:" (name (:directive parsed)) " is deprecated.")))
    (schema/deprecated-syntax? parsed)
    (conj (error :deprecated-syntax
                 (str "The colon form of pebblestream:" (name (:directive parsed))
                      " is deprecated; it is written with parentheses.")))))

(defn- registry-errors [parsed]
  (when-let [explained (schema/explain parsed)]
    [(error :signature
            (str "pebblestream:" (name (:directive parsed))
                 " does not match its signature: " (pr-str explained))
            :details explained)]))

;; ========================================================
;; Parsed form -> JSON
;; ========================================================

(defn- arg->json [x]
  (cond
    (or (string? x) (number? x)) x
    (contains? x :ref)           {"ref" (:ref x)}
    (contains? x :operator)      {"operator" (:operator x) "column" (arg->json (:column x))}
    :else                        x))

(defn- fixed->json [{:keys [required optional]} args]
  (let [ks (concat required optional)]
    (cond
      (< (count args) (count required))
      {:error (error :missing-argument (str "Expected at least " (count required) " arguments."))}
      (> (count args) (count ks))
      {:error (error :extra-argument (str "Expected at most " (count ks) " arguments."))}
      :else
      {:keys (map vector ks (map arg->json args))})))

(defn- many->json [{:keys [key optional]} args]
  (cond
    (seq args) {:keys [[key (mapv arg->json args)]]}
    optional   {:keys []}
    :else      {:error (error :missing-argument "Expected at least one argument.")}))

(defn- pairs->json [{:keys [prefix key fields optional]} args]
  (let [n    (count prefix)
        tail (drop n args)]
    (cond
      (< (count args) n)
      {:error (error :missing-argument (str "Expected at least " n " arguments."))}
      (odd? (count tail))
      {:error (error :unpaired-argument (str "The arguments after the first " n " come in pairs."))}
      (and (empty? tail) (not optional))
      {:error (error :missing-argument "Expected at least one pair of arguments.")}
      :else
      {:keys (concat (map vector prefix (map arg->json args))
                     (when (seq tail)
                       [[key (mapv (fn [[a b]] {(first fields) (arg->json a) (second fields) (arg->json b)})
                                   (partition 2 tail))]]))})))

(defn- conditions->json [args]
  (cond
    (= 1 (count args)) {:keys [["conditions" [{"column" (arg->json (first args))}]]]}
    (and (seq args) (even? (count args)))
    {:keys [["conditions" (mapv (fn [[c p]] {"column" (arg->json c) "predicate" (arg->json p)})
                                (partition 2 args))]]}
    :else
    {:error (error :unpaired-argument
                   "A filter takes one column alone, or column and predicate pairs.")}))

(defn- sort-columns->json [args]
  (if (empty? args)
    {:error (error :missing-argument "Expected at least one sort column.")}
    {:keys [["columns" (mapv (fn [a]
                               (if (and (map? a) (contains? a :order))
                                 {"column" (arg->json (:column a)) "order" (name (:order a))}
                                 {"column" (arg->json a)}))
                             args)]]}))

(defn- sorted->json [args]
  (case (count args)
    0 {:keys []}
    1 (let [a (first args)]
        {:keys [[(if (number? a) "priority" "order") (arg->json a)]]})
    2 {:keys [["order" (arg->json (first args))] ["priority" (arg->json (second args))]]}
    {:error (error :extra-argument "sorted takes at most an order and a priority.")}))

(defn parsed->json
  "Converts a parsed directive to JSON Notation. Returns {:value m} or {:error e}.
   Does not check the registry; see `text->json`."
  [{:keys [directive args]}]
  (if-let [spec (get notation directive)]
    (let [{:keys [keys error]}
          (case (:shape spec)
            :fixed        (fixed->json spec args)
            :many         (many->json spec args)
            :pairs        (pairs->json spec args)
            :conditions   (conditions->json args)
            :sort-columns (sort-columns->json args)
            :sorted       (sorted->json args))]
      (if error
        {:error error}
        {:value (into (array-map "directive" (name directive)) keys)}))
    {:error (error :no-notation
                   (str "pebblestream:" (if (= :div-0 directive) "div/0" (name directive))
                        " has no JSON Notation."
                        (when-let [r (no-notation-reason directive)] (str " " r))))}))

;; ========================================================
;; JSON -> parsed form
;; ========================================================

(defn- json-arg
  "Reads one JSON argument value into its parsed form, or returns {:error ...}."
  [v path]
  (cond
    (and (string? v) (re-find #"[\r\n]" v))
    {:error (error :line-break
                   (str (pr-str path) " contains a line break. A directive is one line of a header note."))}
    (string? v) {:value v}
    (number? v) {:value v}
    (nil? v)    {:error (error :null (str (pr-str path) " is null. Omit an absent optional argument."))}
    (and (map? v) (= #{"ref"} (set (keys v))) (string? (get v "ref")))
    (if (re-matches text/cell-ref-re (get v "ref"))
      {:value {:ref (get v "ref")}}
      {:error (error :invalid-ref (str (pr-str path) " is not one worksheet-qualified cell in A1 notation, such as Inputs!B2: " (get v "ref")))})
    :else
    {:error (error :invalid-value
                   (str (pr-str path) " must be a string, a number or a {\"ref\": ...} object."))}))

(defn- json-predicate [v path]
  (if (and (map? v) (contains? v "operator"))
    (let [extra (disj (set (keys v)) "operator" "column")
          op    (get v "operator")
          col   (get v "column")]
      (cond
        (seq extra)               {:error (error :unknown-key (str (pr-str path) " has unknown keys " (pr-str (sort extra))))}
        (not (contains? text/operators op))
        {:error (error :invalid-predicate
                       (str (pr-str (conj path "operator")) " must be one of = <> < <= > >=."))}
        (not (string? col))       {:error (error :invalid-predicate
                                                 (str (pr-str (conj path "column")) " must be a header."))}
        (re-find #"[\r\n]" col)   {:error (error :line-break
                                                 (str (pr-str (conj path "column")) " contains a line break."))}
        :else                     {:value {:operator op :column col}}))
    (json-arg v path)))

(defn- collect
  "Turns a seq of {:value v} / {:error e} into {:values [...]} or {:errors [...]}."
  [results]
  (let [errors (keep :error results)]
    (if (seq errors) {:errors (vec errors)} {:values (mapv :value results)})))

(defn- object-items
  "Checks an array of objects whose keys are drawn from `fields`. Returns
   {:items [...]} or {:errors [...]}."
  [m key fields required path]
  (let [v (get m key)]
    (cond
      (not (sequential? v))
      {:errors [(error :invalid-value (str (pr-str (conj path key)) " must be an array."))]}
      (empty? v)
      {:errors [(error :missing-argument (str (pr-str (conj path key)) " must have at least one item."))]}
      :else
      (let [errors (for [[i item] (map-indexed vector v)
                         e (cond
                             (not (map? item))
                             [(error :invalid-value (str (pr-str (conj path key i)) " must be an object."))]
                             :else
                             (concat
                              (for [k (sort (remove (set fields) (keys item)))]
                                (error :unknown-key (str (pr-str (conj path key i)) " has unknown key " (pr-str k))))
                              (for [k required :when (not (contains? item k))]
                                (error :missing-key (str (pr-str (conj path key i)) " needs " (pr-str k))))))]
                     e)]
        (if (seq errors) {:errors (vec errors)} {:items (vec v)})))))

(defn- json->args [spec m]
  (case (:shape spec)
    :fixed
    (let [{:keys [required optional]} spec
          missing (remove #(contains? m %) required)
          present (take-while #(contains? m %) optional)
          gap     (filter #(contains? m %) (drop (count present) optional))]
      (cond
        (seq missing) {:errors (mapv #(error :missing-key (str "Required key " (pr-str %) " is missing.")) missing)}
        (seq gap)     {:errors [(error :missing-key
                                       (str (pr-str (first gap)) " can be given only with "
                                            (pr-str (nth optional (count present)))))]}
        :else         (collect (map #(json-arg (get m %) [%]) (concat required present)))))

    :many
    (let [{:keys [key optional]} spec
          v (get m key)]
      (cond
        (and (nil? v) (not (contains? m key)) optional) {:values []}
        (not (sequential? v)) {:errors [(error :invalid-value (str (pr-str key) " must be an array."))]}
        (and (empty? v) (not optional))
        {:errors [(error :missing-argument (str (pr-str key) " must have at least one item."))]}
        :else (collect (map-indexed (fn [i x] (json-arg x [key i])) v))))

    :pairs
    (let [{:keys [prefix key fields optional]} spec
          missing (remove #(contains? m %) prefix)]
      (cond
        (seq missing)
        {:errors (mapv #(error :missing-key (str "Required key " (pr-str %) " is missing.")) missing)}
        (and optional (not (contains? m key)))
        (collect (map #(json-arg (get m %) [%]) prefix))
        :else
        (let [{:keys [items errors]} (object-items m key fields fields [])]
          (if errors
            {:errors errors}
            (collect (concat (map #(json-arg (get m %) [%]) prefix)
                             (for [[i item] (map-indexed vector items)
                                   f fields]
                               (json-arg (get item f) [key i f]))))))))

    :conditions
    (let [{:keys [items errors]} (object-items m "conditions" ["column" "predicate"] ["column"] [])]
      (cond
        errors {:errors errors}
        (and (> (count items) 1) (some #(not (contains? % "predicate")) items))
        {:errors [(error :missing-key
                         "A condition without \"predicate\" must be the directive's only condition.")]}
        (= 1 (count items))
        (let [item (first items)]
          (collect (cond-> [(json-arg (get item "column") ["conditions" 0 "column"])]
                     (contains? item "predicate")
                     (conj (json-predicate (get item "predicate") ["conditions" 0 "predicate"])))))
        :else
        (collect (for [[i item] (map-indexed vector items)
                       r [(json-arg (get item "column") ["conditions" i "column"])
                          (json-predicate (get item "predicate") ["conditions" i "predicate"])]]
                   r))))

    :sort-columns
    (let [{:keys [items errors]} (object-items m "columns" ["column" "order"] ["column"] [])]
      (if errors
        {:errors errors}
        (collect (for [[i item] (map-indexed vector items)]
                   (let [{value :value err :error} (json-arg (get item "column") ["columns" i "column"])
                         order (get item "order")]
                     (cond
                       err                             {:error err}
                       (not (contains? item "order"))  {:value value}
                       (#{"asc" "desc"} order)         {:value {:column value :order (keyword order)}}
                       :else {:error (error :invalid-value
                                            (str (pr-str ["columns" i "order"]) " must be \"asc\" or \"desc\"."))}))))))

    :sorted
    (cond
      (and (contains? m "order") (not (#{"asc" "desc"} (get m "order"))))
      {:errors [(error :invalid-value "\"order\" must be \"asc\" or \"desc\".")]}
      :else
      (collect (concat (when (contains? m "order") [(json-arg (get m "order") ["order"])])
                       (when (contains? m "priority") [(json-arg (get m "priority") ["priority"])]))))))

(defn json->parsed
  "Converts one JSON Notation directive to its parsed form. Returns
   {:directive parsed} or {:errors [...]}. Does not check the registry; see
   `json->text`."
  [m]
  (let [name (get m "directive")
        k    (when (string? name) (keyword name))
        spec (get notation k)]
    (cond
      (not (map? m))
      {:errors [(error :invalid-value "A directive is a JSON object.")]}
      (not (string? name))
      {:errors [(error :missing-key "A directive needs a \"directive\" key naming it.")]}
      (string/starts-with? name "pebblestream:")
      {:errors [(error :invalid-name "\"directive\" names the directive without the pebblestream: prefix.")]}
      (nil? spec)
      {:errors [(error (if (contains? schema/all-directives k) :no-notation :unknown-directive)
                       (str name (if (contains? schema/all-directives k)
                                   (str " has no JSON Notation. " (no-notation-reason k))
                                   " is not a PebbleScript directive.")))]}
      :else
      (let [unknown (sort (remove (allowed-keys spec) (keys m)))]
        (if (seq unknown)
          {:errors (mapv #(error :unknown-key (str name " has no key " (pr-str %) ".")) unknown)}
          (let [{:keys [values errors]} (json->args spec m)
                bare? (contains? (get-in schema/all-directives [k :syntax]) :bare)]
            (if errors
              {:errors errors}
              {:directive {:directive k
                           :syntax    (if (and (empty? values) bare?) :bare :paren)
                           :args      values}})))))))

;; ========================================================
;; Directive lines
;; ========================================================

(defn text->json
  "Converts one textual directive line to JSON Notation."
  [line]
  (let [{:keys [directive errors warnings]} (text/parse line)]
    (if errors
      (failure errors warnings)
      (let [all-warnings (concat warnings (registry-warnings directive))]
        (if-let [errs (seq (registry-errors directive))]
          (failure errs all-warnings)
          (let [{:keys [value error]} (parsed->json directive)]
            (if error
              (failure [error] all-warnings)
              (success value all-warnings))))))))

(defn json->text
  "Converts one JSON Notation directive to canonical textual PebbleScript."
  [m]
  (let [{:keys [directive errors]} (json->parsed m)]
    (if errors
      (failure errors [])
      (if-let [errs (seq (registry-errors directive))]
        (failure errs [])
        (success (text/render directive) (registry-warnings directive))))))

;; ========================================================
;; Annotations
;; ========================================================

(defn- lines-of [annotation]
  (cond
    (sequential? annotation) (vec annotation)
    (= "" annotation)        []
    :else                    (string/split (string/replace annotation "\r\n" "\n") #"\n" -1)))

(defn- at-line [n items] (map #(assoc % :line n) items))

(defn annotation->json
  "Converts a header note (a string, or a sequence of lines) to a JSON
   Notation array of directive and comment objects, in order. Errors and
   warnings carry the 1-based :line they come from."
  [annotation]
  (let [results (map-indexed
                 (fn [i line]
                   (if (text/directive-line? line)
                     (let [r (text->json line)]
                       (assoc r :line (inc i)))
                     {:value {"comment" line} :errors [] :warnings [] :line (inc i)}))
                 (lines-of annotation))
        errors   (mapcat #(at-line (:line %) (:errors %)) results)
        warnings (mapcat #(at-line (:line %) (:warnings %)) results)]
    (if (seq errors)
      (failure errors warnings)
      (success (mapv :value results) warnings))))

(defn- comment->line [m i]
  (let [c (get m "comment")]
    (cond
      (not= #{"comment"} (set (keys m)))
      {:error (error :unknown-key "A comment object has only a \"comment\" key." :item i)}
      (not (string? c))
      {:error (error :invalid-value "\"comment\" must be a string." :item i)}
      (re-find #"[\r\n]" c)
      {:error (error :line-break "A comment holds one line; it may not contain a line break." :item i)}
      (text/directive-line? c)
      {:error (error :comment-is-directive
                     "A comment may not begin with pebblestream:, since it would be read back as a directive."
                     :item i)}
      :else {:value c})))

(defn json->annotation
  "Converts a JSON Notation annotation array to the text of a header note.
   Errors and warnings carry the 0-based :item they come from."
  [items]
  (if-not (sequential? items)
    (failure [(error :invalid-value "An annotation is a JSON array.")] [])
    (let [results (map-indexed
                   (fn [i m]
                     (cond
                       (and (map? m) (contains? m "comment")) (comment->line m i)
                       (map? m)
                       (let [{:keys [value errors warnings]} (json->text m)]
                         {:value    value
                          :errors   (map #(assoc % :item i) errors)
                          :warnings (map #(assoc % :item i) warnings)})
                       :else {:error (error :invalid-value "Each item is a directive or comment object." :item i)}))
                   items)
          errors   (concat (keep :error results) (mapcat :errors results))
          warnings (mapcat :warnings results)]
      (if (seq errors)
        (failure errors warnings)
        (success (string/join "\n" (map :value results)) warnings)))))
