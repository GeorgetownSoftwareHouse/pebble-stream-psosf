;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.schema
  "Malli schemas for PebbleScript directives.

   Three layers:

   1. `param-registry` — one Malli schema per parameter type.
   2. `directives`     — one entry per directive: its type, accepted syntax,
                         requires/excludes and an argument schema written as a
                         Malli sequence schema (:cat, :+, :*, :?).
   3. `parsed-directive` — a :multi schema validating a parsed directive of the
                         form {:directive :from, :syntax :paren, :args [\"Accounts\"]}.

   Markers (Malli properties):

   :pebblestream/ambiguous     A string explaining what is unclear. Wherever the
                               source was unclear the accepted type defaults to
                               :string. Run `ambiguities` to list every marker.
   :pebblestream/unique-class  Parameters sharing a class number are believed to
                               require distinct values (from the source's
                               :classes {:unique n}). Not enforced by Malli.
   :pebblestream/sheet-ref     The column belongs to the sheet named by the
                               parameter at this index (from the source's
                               :tags {:sheet-id n}). Not enforced by Malli.
   :pebblestream/source        :supplied-spec for directives taken from the
                               editor's specs; :not-in-supplied-spec for
                               directives known to exist but absent from them.

   Syntax:

   A directive whose source :style is :colon accepts both forms,
     pebblestream:name:a,b        (:colon, deprecated)
     pebblestream:name(a, b)      (:paren)
   A directive whose source :style is :paren accepts only :paren.

   Any directive that takes no arguments, or whose arguments are all optional,
   may also be written bare:
     pebblestream:unique          (:bare, not deprecated)
     pebblestream:unique()        (:paren)
     pebblestream:coalesce        (:bare)
   The bare form is valid only with no arguments.

   Quoting: a quoted string is accepted for every string argument. A worksheet
   or column name that contains no space may also be written unquoted. Parsed
   arguments are the same either way; JSON Notation renders every string quoted.
   Inside quotes, a quote is written doubled (\"\") or escaped (\\\"), and a
   backslash escapes the next character, so a literal backslash is doubled. A
   backslash before any other character is removed and draws an
   :ambiguous-backslash warning (see pebblestream.parse-str).

   Exclusions are written on both directives: if A excludes B, B excludes A.
   `excludes-errors` reports any one-way pair."
  (:require
   [clojure.string]
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [malli.util :as mu]))

;; ========================================================
;; Markers
;; ========================================================

(defn- ambiguous
  "Properties map marking a schema as ambiguous, with the reason."
  [reason]
  {:pebblestream/ambiguous reason})

;; ========================================================
;; Parameter types
;; ========================================================

(def ^:private excel-sheet-name
  "Excel's sheet-name rules: 1–31 characters, none of \\ / ? * [ ] :"
  [:re "^[^\\\\/?*\\[\\]:]{1,31}$"])

(def param-registry
  {:ps/sheet
   [:and {:description "The name of a worksheet in the same spreadsheet."}
    :string excel-sheet-name]

   :ps/sheet-named
   [:and (merge {:description "The name of a worksheet whose columns later parameters refer to (see :pebblestream/sheet-ref)."}
                (ambiguous "How :sheet-named differs from :sheet beyond being the target of :sheet-id tags is undocumented."))
    :string excel-sheet-name]

   :ps/column-derived
   [:string (merge {:description "The header of an existing column."}
                   (ambiguous "Which sheet the column must be on (the current sheet, the :from sheet, or either) is undocumented."))]

   :ps/column-derived-sort
   [:or {:description (str "A column to sort by. Written \"Date\" (ascending, the default) or with an order "
                           "suffix, \"Date\":desc or \"Date\":asc. Parsed as the header string, or as "
                           "{:column \"Date\" :order :desc} when a suffix is present. Columns sort in the order given.")}
    :string
    [:map {:closed true}
     [:column [:or :string [:schema (ambiguous "Unclear whether a cell reference can carry an order suffix (Inputs!B2:desc).")
                            :ps/cell-ref]]]
     [:order [:enum :asc :desc]]]]

   :ps/column-new
   [:string {:description "The header of a column the directive creates or fills."}]

   :ps/column-source
   [:string {:description "The header of a column on the sheet named by the :pebblestream/sheet-ref parameter."}]

   :ps/allowed-value
   [:string (merge {:description "One permitted value for the column."}
                   (ambiguous "Whether numbers, booleans and dates are accepted as typed literals or only as strings is undocumented."))]

   :ps/predicate
   [:or {:description (str "A condition applied to the preceding column: a literal predicate such as \">0\" "
                           "or \"blue\", or a column predicate \"<=\"&\"Balance\" comparing with another "
                           "column in the same row, parsed as {:operator \"<=\" :column \"Balance\"}.")}
    [:string (ambiguous "The full grammar of literal predicates (operators, quoting, numbers versus text) is undocumented.")]
    [:map {:closed true}
     [:operator [:enum (ambiguous "Operators assumed to be Excel's comparison operators; documented examples show > and <=.")
                 "=" "<>" "<" "<=" ">" ">="]]
     [:column :string]]]

   :ps/sort-order
   [:enum {:description "Sort order: asc or desc."} "asc" "desc"]

   :ps/hint-key
   [:string {:description "The hint's key."}]

   :ps/hint-value
   [:string {:description "The hint's value; hints sharing a key on one worksheet collect into a list."}]

   :ps/number
   [number? (ambiguous "Whether this must be a positive integer (a row count) or any number is undocumented.")]

   :ps/paths-op
   [:string (ambiguous "The set of permitted paths operations is undocumented; likely an enum.")]

   :ps/cell-value
   [:string (ambiguous "Any argument may be a cell reference; unclear how a cell value differs from other literal strings.")]

   :ps/cell-ref
   [:map (merge {:closed true
                 :description "A cell reference given in place of a literal argument, parsed as {:ref \"Inputs!B2\"}. Always qualified by its worksheet name."}
                (ambiguous "Unclear whether ranges, named ranges, whole columns or formulas are accepted, and whether the referenced cell is read at compile time or run time."))
    [:ref [:re "^(?:'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!\\$?[A-Za-z]{1,3}\\$?[0-9]+$"]]]

   :ps/filter-data-type
   [:string (ambiguous "Likely an enum of data types (text, number, boolean, date); undocumented.")]

   :ps/filter-value
   [:string (ambiguous "Type depends on the preceding :filter-data-type; undocumented.")]

   :ps/regex
   [:string {:description "A regular expression."}]

   :ps/template-name
   [:string {:description "The name of a template."}]

   :ps/style-header
   [:string (ambiguous "Whether this is a header name, a Zen palette style name, or a header-to-style mapping is undocumented.")]

   :ps/url-path
   [:string {:description "A URL path, literal or from a cell."}]

   :ps/query-name
   [:string {:description "A query parameter name."}]

   :ps/query-value
   [:string {:description "A query parameter value."}]

   :ps/filename
   [:string {:description "A file name."}]

   :ps/part-name
   [:string {:description "The name of a request field."}]

   :ps/part-value
   [:string {:description "A field value."}]

   :ps/unknown
   [:string (ambiguous "Parameter type unknown; the directive was not in the supplied spec.")]})

(def registry
  (mr/composite-registry (m/default-schemas) param-registry))

;; ========================================================
;; Parameter helpers
;; ========================================================

(defn- p
  "A parameter of type `t`, with optional unique class and sheet reference.
   Every parameter also accepts a cell reference in place of a literal."
  ([t] (p t {}))
  ([t {:keys [unique sheet-ref]}]
   [:or (cond-> {}
          unique    (assoc :pebblestream/unique-class unique)
          sheet-ref (assoc :pebblestream/sheet-ref sheet-ref))
    (keyword "ps" (name t))
    :ps/cell-ref]))

(def ^:private no-args [:cat])

(defn- group-aggregate
  "Signature shared by sum, min, max, average, mult, equal and ratio:
   a source column and an optional new column."
  []
  [:cat (p :column-derived) [:? (p :column-new)]])

(defn- four-derived-columns []
  [:cat
   (p :column-derived {:unique 0}) (p :column-derived {:unique 0})
   (p :column-derived {:unique 0}) (p :column-derived {:unique 0})])

(defn- join-args [directive]
  [:cat
   (p :sheet-named {:sheet-ref 0})
   ;; Column pairs are optional: without them, rows match on the headers both
   ;; worksheets share. Each of join and outer-join may be used once per worksheet.
   [:*
    [:cat (p :column-derived {:unique 1}) (p :column-source {:unique 2 :sheet-ref 0})]]])

(defn- unknown-args []
  [:* (ambiguous "Signature unknown; the directive was not in the supplied spec. Accepts any strings.")
   (p :unknown)])

;; ========================================================
;; Directives
;; ========================================================

;; :bare is dropped by `normalize-syntax` for directives that require arguments.
(def ^:private colon #{:colon :paren :bare})
(def ^:private paren #{:paren :bare})

(def directives
  "Directive keyword -> entry. :syntax is the set of accepted surface forms;
   :colon is accepted only for backward compatibility and is deprecated."
  {;; ---- Transformative: sheet sources ------------------------------------
   :from
   {:directive-type :transformative :syntax paren
    :excludes #{:match-rows :stop :generate-rows :http :files :sql :queue :email}
    :args [:+ (p :sheet {:unique 0})]}

   :cartesian-product
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args [:+ (p :sheet {:unique 0})]}

   :join
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args (join-args :join)}

   :outer-join
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args (join-args :outer-join)}

   ;; ---- Transformative: row selection and ordering -----------------------
   ;; Separate filter directives on one worksheet are combined with OR.
   :filter
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args [:alt
            ;; A lone column keeps rows where its value is TRUE.
            [:cat (p :column-derived)]
            ;; Otherwise column and predicate pairs, all of which must hold.
            [:+ [:cat (p :column-derived) (p :predicate)]]]}

   ;; prune is the same as filter, but always runs last.
   :prune
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args [:alt
            ;; A lone column keeps rows where its value is TRUE.
            [:cat (p :column-derived)]
            ;; Otherwise column and predicate pairs, all of which must hold.
            [:+ [:cat (p :column-derived) (p :predicate)]]]}

   :sort
   {:directive-type :transformative :syntax paren :requires #{:from}
    :excludes #{:group}
    :args [:+ (p :column-derived-sort {:unique 0})]}

   :header-map
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args [:+ [:cat (p :column-derived {:unique 0}) (p :column-new {:unique 1})]]}

   ;; ---- Transformative: grouping -----------------------------------------
   :group
   {:directive-type :transformative :syntax paren :requires #{:from}
    :excludes #{:sort :group-unique :group-sum :group-ratio}
    :args [:+ (p :column-derived {:unique 0})]}

   :group-unique
   {:directive-type :transformative :syntax paren :requires #{:from}
    :excludes #{:group :group-sum :group-ratio}
    :args [:+ (p :column-derived {:unique 0})]}

   :group-sum
   {:directive-type :transformative :syntax paren :requires #{:from}
    :excludes #{:group :group-unique :group-ratio}
    :args [:+ (p :column-derived {:unique 0})]}

   :group-ratio
   {:directive-type :transformative :syntax paren :requires #{:from}
    :excludes #{:group :group-unique :group-sum}
    :args [:+ (p :column-derived {:unique 0})]}

   ;; ---- Transformative: group aggregates ---------------------------------
   :sum     {:directive-type :transformative :syntax paren :requires #{:group} :args (group-aggregate)}
   :min     {:directive-type :transformative :syntax paren :requires #{:group} :args (group-aggregate)}
   :max     {:directive-type :transformative :syntax paren :requires #{:group} :args (group-aggregate)}
   :average {:directive-type :transformative :syntax paren :requires #{:group} :args (group-aggregate)}
   :mult    {:directive-type :transformative :syntax paren :requires #{:group} :args (group-aggregate)}
   :equal   {:directive-type :transformative :syntax paren :requires #{:group} :args (group-aggregate)}

   :ratio
   {:directive-type :transformative :syntax paren :requires #{:group}
    :excludes #{:coalesce}
    :args (group-aggregate)}

   :balance
   {:directive-type :transformative :syntax paren :requires #{:group}
    :excludes #{:coalesce}
    :args [:cat (p :column-derived) (p :column-new) (p :column-new)]}

   :coalesce
   {:directive-type :transformative :syntax paren :requires #{:group}
    :excludes #{:expand :ratio :balance}
    :args no-args}

   :expand
   {:directive-type :transformative :syntax paren :requires #{:group}
    :excludes #{:coalesce}
    :args no-args}

   :yield
   {:directive-type :transformative :syntax paren :requires #{:from :group}
    :args [:cat (ambiguous "Source gives parameters 3 and 4 the same unique class (3); unclear whether they must differ from each other or from parameters in other classes.")
           (p :column-derived {:unique 1}) (p :column-derived {:unique 2})
           (p :column-derived {:unique 3}) (p :column-derived {:unique 3})
           (p :column-new)]}

   :npv
   {:directive-type :transformative :syntax paren :requires #{:from :group}
    :args [:cat (ambiguous "Source gives parameters 3 and 4 the same unique class (3); unclear whether they must differ from each other or from parameters in other classes.")
           (p :column-derived {:unique 1}) (p :column-derived {:unique 2})
           (p :column-derived {:unique 3}) (p :column-derived {:unique 3})
           (p :column-new)]}

   :pivot
   {:directive-type :transformative :syntax paren :requires #{:from :group}
    :args [:cat (ambiguous "The optional filter values share unique class 1 with the first column parameter; the intended constraint is unclear.")
           (p :column-derived {:unique 1}) (p :cell-value)
           (p :column-derived {:unique 3}) (p :filter-data-type)
           [:? (p :filter-value {:unique 1})]]}

   ;; ---- Transformative: allocation and paths -----------------------------
   :apportion {:directive-type :transformative :syntax paren :requires #{:from} :args (four-derived-columns)}
   :relieve   {:directive-type :transformative :syntax paren :requires #{:from} :args (four-derived-columns)}

   :paths
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args [:cat (p :column-derived {:unique 0}) (p :column-derived {:unique 0}) (p :column-derived {:unique 0})]}

   :paths-operation
   {:directive-type :transformative :syntax paren :requires #{:from}
    :args [:cat (p :paths-op) (p :column-new)]}

   :regex
   {:directive-type :transformative :syntax paren :requires #{:group}
    :status :disabled
    :args [:cat (p :regex) (p :column-derived) [:? (p :column-new)]]}

   ;; ---- Generative ---------------------------------------------------------
   ;; An explicit match-rows may not be combined with a transformative directive.
   ;; Formula columns on a transformative worksheet are always dragged down to
   ;; the transformed rows after the transformative directives run. Authors never
   ;; write this; the analyzer declares it as a self match-rows during compilation.
   :match-rows
   {:directive-type :generative :syntax colon
    :excludes #{:from :stop :http :files :sql :queue :email}
    :args [:cat (p :sheet)]}

   :generate-rows
   {:directive-type :generative :syntax paren
    :excludes #{:from :stop :http :files :sql :queue :email}
    :args [:cat (p :number)]}

   ;; stop may appear on more than one column. The first column, in execution
   ;; order (not left-to-right position), that evaluates to true short-circuits
   ;; the worksheet's execution. The optional number is
   ;; the most rows to generate; when omitted, the runtime's configured limit
   ;; applies. Best practice is always to set it.
   :stop
   {:directive-type :generative :syntax paren
    :excludes #{:from :match-rows :generate-rows :http :files :sql :queue :email}
    :args [:cat
           [:? [:schema {:description "Maximum rows to generate; defaults to the runtime's configured limit."}
                (p :number)]]]}

   ;; ---- Annotative ---------------------------------------------------------
   :unique            {:directive-type :annotative :syntax colon :args no-args}
   :grouped           {:directive-type :annotative :syntax colon :args no-args}
   :warn              {:directive-type :annotative :syntax colon :args no-args}
   :fail              {:directive-type :annotative :syntax colon :args no-args}
   :not-blank         {:directive-type :annotative :syntax colon :args no-args}
   :ignore            {:directive-type :annotative :syntax colon :args no-args}
   ;; sorted[:(asc|desc)][:<number>]: optional order, then an optional priority
   ;; for breaking ties when several columns carry sorted.
   :sorted
   {:directive-type :annotative :syntax colon
    :args [:cat [:? (p :sort-order)] [:? (p :number)]]}
   :do-not-compile    {:directive-type :annotative :syntax colon :args no-args}
   :out               {:directive-type :annotative :syntax colon :args no-args}

   :allowed-values
   {:directive-type :annotative :syntax colon
    :args [:+ (p :allowed-value)]}

   :hint
   {:directive-type :annotative :syntax colon
    :args [:cat (p :hint-key) [:? (p :hint-value)]]}

   :identical-columns
   {:directive-type :annotative :syntax colon
    :args [:+ (p :sheet)]}

   ;; ---- Annotative: templates --------------------------------------------
   :template-name
   {:directive-type :annotative :syntax colon
    :args [:cat (p :template-name)]}

   :block-id                    {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :template-row                {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :template-column             {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :leftmost-column             {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :rightmost-column            {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :top-row                     {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :bottom-row                  {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :block-row-count             {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :template-out-header         {:directive-type :annotative :syntax colon :requires #{:template-name}
                                 :excludes #{:template-out-generate-clean} :args no-args}
   :template-out-cell           {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :template-out-group          {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}
   :template-out-value-type     {:directive-type :annotative :syntax colon :requires #{:template-name} :args no-args}

   :row-oriented-headers
   {:directive-type :annotative :syntax colon :requires #{:template-name}
    :excludes #{:column-oriented-headers} :args no-args}

   :column-oriented-headers
   {:directive-type :annotative :syntax colon :requires #{:template-name}
    :excludes #{:row-oriented-headers} :args no-args}

   :template-out-down
   {:directive-type :annotative :syntax colon :requires #{:template-name}
    :excludes #{:template-out-right} :args no-args}

   :template-out-right
   {:directive-type :annotative :syntax colon :requires #{:template-name}
    :excludes #{:template-out-down} :args no-args}

   :template-out-generate-clean
   {:directive-type :annotative :syntax colon :requires #{:template-name}
    :excludes #{:template-out-header} :args no-args}

   :template-out-style
   {:directive-type :annotative :syntax colon :requires #{:template-name}
    :args [:* (ambiguous "Source :quant is :many (zero or more); a template-out-style with no styles validates. Possibly :+.")
           (p :style-header)]}})

;; ========================================================
;; Connector directives (proposed)
;; ========================================================
;;
;; Drafted from the connector Architecture & Design document and Implementation
;; Specification. Not yet implemented: every entry carries :status :proposed.
;;
;; A connector sheet's first directive is its verb (http, files or sql). The verb
;; is transformative and takes the place of from: the response becomes the
;; sheet's rows. Headers that are exactly pfr:path, pfr:field, pfr:raw receive
;; the response in PFR form; ordinary headers receive matching columns. A
;; connector sheet is volatile. Sub-directives belong to one or more verbs and
;; are only valid on a sheet with one of them (:requires-any).

(def ^:private connector-verbs #{:http :files :sql :queue :email})

(def ^:private connection-name
  "A connection is named by a literal string, never a cell reference; the
   gateway resolves it."
  [:string {:min 1 :pebblestream/param-type :connection
            :description "Connection name, as created in the Zen connection form."}])

(def ^:private duration
  [:re {:pebblestream/param-type :duration
        :description "A duration: a whole number of seconds, minutes or hours, e.g. \"30s\", \"5m\", \"2h\"."}
   "^[1-9][0-9]*(s|m|h)$"])

(def ^:private poll-condition
  "A stop condition for poll. Several conditions combine with OR."
  [:multi {:dispatch :condition :pebblestream/param-type :poll-condition}
   [:http-status [:map {:closed true} [:condition [:= :http-status]]
                  [:value [:re {:description "A status code, or a class such as 2xx."} "^[1-5]([0-9]{2}|xx)$"]]]]
   [:not-empty   [:map {:closed true} [:condition [:= :not-empty]]]]
   [:field       [:map {:closed true} [:condition [:= :field]] [:name :string] [:value :string]]]
   [:file-exists [:map {:closed true} [:condition [:= :file-exists]] [:pattern :string]]]
   [:file-stable [:map {:closed true} [:condition [:= :file-stable]] [:pattern :string] [:for duration]]]])

(def ^:private criterion
  "A sql where criterion: a literal (\"VIP\", \">0\"), an operator joined to a
   column (\"<\"&\"Name\") or to a cell (\"<\"&Inputs!B2)."
  [:or {:pebblestream/param-type :criterion}
   :string
   [:map {:closed true} [:operator [:enum "=" "<>" "<" "<=" ">" ">="]] [:column :string]]
   [:map {:closed true} [:operator [:enum "=" "<>" "<" "<=" ">" ">="]] [:ref :string]]])

(def ^:private proposed
  {:directive-type :transformative :syntax paren :status :proposed})

(def connector-directives
  {;; ---- http -------------------------------------------------------------
   ;; http(Conn, path, [name, value]*)
   :http
   (assoc proposed
          :excludes #{:from :match-rows :generate-rows :stop :files :sql :queue :email}
          :args [:cat connection-name
                 [:schema {:description "Path, appended to the connection's base URL."} (p :url-path)]
                 [:* [:cat (p :query-name) (p :query-value)]]])

   :method
   (assoc proposed :requires-any #{:http}
          :args [:cat [:enum {:description "Default: post when the sheet has send, otherwise get."}
                       "get" "post" "put" "patch" "delete"]])

   ;; send(Sheet, file, enc [, filename]) | send("name", field, value) | send(Sheet, body, enc)
   ;; At most one body, and a body cannot be combined with file or field parts.
   :send
   (assoc proposed :requires-any #{:http}
          :args [:alt
                 [:cat (p :sheet) [:= "file"] [:enum "csv" "json"] [:? (p :filename)]]
                 [:cat (p :part-name) [:= "field"] (p :part-value)]
                 [:cat (p :sheet) [:= "body"] [:enum "csv" "json"]]])

   :expect
   (assoc proposed :requires-any #{:http}
          :args [:+ [:int {:min 100 :max 599}]])

   :response
   (assoc proposed :requires-any #{:http}
          :args [:cat [:enum "auto" "json" "csv"]])

   ;; http: poll(ref, interval, timeout, condition…, [:get|:post])
   ;; files, sql: poll(interval, timeout, condition…)
   :poll
   (assoc proposed :requires-any #{:http :files :sql}
          :args [:alt
                 [:cat [:schema {:description "PFR path in the submit response holding the URL to poll."} :string]
                  duration duration [:+ poll-condition] [:? [:enum :get :post]]]
                 [:cat duration duration [:+ poll-condition]]])

   ;; callback(field, timeout [, auth-field]); Flow only.
   :callback
   (assoc proposed :requires-any #{:http}
          :args [:cat (p :part-name) duration [:? (p :part-name)]])

   ;; ---- files ------------------------------------------------------------
   :files
   (assoc proposed
          :excludes #{:from :match-rows :generate-rows :stop :http :sql :queue :email}
          :args [:cat connection-name])

   :directory {:directive-type :transformative :syntax paren :status :proposed :requires-any #{:files}
               :args [:cat [:string {:description "Directory under the connection's root; cannot escape it."}]]}

   :upload
   (assoc proposed :requires-any #{:files}
          :args [:cat (p :sheet) (p :filename) [:? [:enum :csv :tsv :csv-no-header :json]]])

   :download
   (assoc proposed :requires-any #{:files}
          :args [:cat [:string {:description "File name pattern, e.g. \"wash_sales_*.csv\"."}] [:? [:= :latest]]])

   :remove (assoc proposed :requires-any #{:files} :args no-args)

   :rename
   (assoc proposed :requires-any #{:files}
          :args [:cat [:string {:description "Prefix to move processed files under, e.g. \"done/\"."}]])

   ;; ---- sql --------------------------------------------------------------
   ;; A sql sheet is a read (select, where, order-by, limit, poll) or a write
   ;; (insert, update, set, call), never both.
   :sql
   (assoc proposed
          :excludes #{:from :match-rows :generate-rows :stop :http :files :queue :email}
          :args [:cat connection-name [:? [:string {:description "Table."}]]])

   :select (assoc proposed :requires-any #{:sql} :args [:+ (p :column-derived)])

   ;; where(col) keeps rows where the column is true; otherwise pairs, ANDed.
   ;; Several where directives are ORed.
   :where
   (assoc proposed :requires-any #{:sql}
          :args [:alt [:cat (p :column-derived)]
                 [:+ [:cat (p :column-derived) criterion]]])

   :order-by
   (assoc proposed :requires-any #{:sql}
          :args [:+ [:cat (p :column-derived) [:? [:enum :asc :desc]]]])

   :limit (assoc proposed :requires-any #{:sql} :args [:cat [:int {:min 1}]])

   :insert
   (assoc proposed :requires-any #{:sql}
          :args [:cat (p :sheet) [:* (p :column-derived)]])

   :update
   (assoc proposed :requires-any #{:sql}
          :args [:cat (p :sheet) [:+ (p :column-derived)] [:? [:= :insert-missing]] [:? [:= :strict]]])

   :set (assoc proposed :requires-any #{:sql} :args [:+ (p :column-derived)])

   :call
   (assoc proposed :requires-any #{:sql}
          :args [:cat [:string {:description "Procedure name."}]
                 [:* [:or (p :part-value) [:enum :request-id :knownat]]]])

   ;; ---- queue and email: sketches only ------------------------------------
   :queue
   (assoc proposed
          :excludes #{:from :match-rows :generate-rows :stop :http :files :sql :email}
          :pebblestream/ambiguous (str "Sketch only. Proposed sub-directives publish(Sheet, json, topic [, :whole]) "
                                       "and consume(topic, max, wait); acknowledgement after the request record "
                                       "is written (at-least-once). Deferred until a customer names a broker.")
          :args [:cat connection-name])

   :email
   (assoc proposed
          :excludes #{:from :match-rows :generate-rows :stop :http :files :sql :queue}
          :pebblestream/ambiguous (str "Sketch only. Proposed sub-directives for sending a sheet as an attachment "
                                       "and receiving attachments from a folder by pattern within a time window; "
                                       "names to be chosen so they do not collide with http's send. Deferred until "
                                       "a customer names a mailbox.")
          :args [:cat connection-name])})

;; Directives defined from docs.pebblestream.com rather than the editor's specs.
(def documented-directives
  {;; Column type overrides.
   :text    {:directive-type :annotative :syntax colon :args no-args}
   :number  {:directive-type :annotative :syntax colon :args no-args}
   :boolean {:directive-type :annotative :syntax colon :args no-args}
   :date    {:directive-type :annotative :syntax colon :args no-args}

   ;; Marks a column as part of a primary key; an optional group name separates
   ;; several keys on one worksheet.
   :id
   {:directive-type :annotative :syntax colon
    ;; Like every argument, the group name may be a cell reference.
    :args [:cat [:? [:schema {:description "Key group name."} [:or :string :ps/cell-ref]]]]}

   ;; How Flow divides the driving input worksheet among executions.
   :workflow-each  {:directive-type :annotative :syntax colon :excludes #{:workflow-all :workflow-group} :args no-args}
   :workflow-group {:directive-type :annotative :syntax colon :excludes #{:workflow-each :workflow-all} :args no-args}
   :workflow-all   {:directive-type :annotative :syntax colon :excludes #{:workflow-each :workflow-group} :args no-args}

   ;; Iteration. workflow-iterate or workflow-terminate marks the iteration
   ;; spreadsheet; a workflow has at most one.
   :workflow-iterate
   {:directive-type :annotative :syntax colon :excludes #{:workflow-terminate}
    :args [:cat (ambiguous "The documentation's example is written pebblestream:iterate:10, not workflow-iterate.")
           [:schema {:description "Number of times to run the workflow."} (p :number)]]}

   :workflow-terminate
   {:directive-type :annotative :syntax colon :excludes #{:workflow-iterate}
    :args [:cat (ambiguous "The documentation's example is written pebblestream:terminate:10, not workflow-terminate.")
           [:schema {:description "Safety limit on the number of iterations."} (p :number)]]}

   :workflow-filter {:directive-type :annotative :syntax colon :args no-args}})

(def missing-directives
  "Directives documented or described as existing but absent from the supplied
   spec. Each accepts any strings until its signature is confirmed."
  (into {}
        (map (fn [[k directive-type]]
               [k {:directive-type      directive-type
                   :syntax              colon
                   :pebblestream/source :not-in-supplied-spec
                   :pebblestream/ambiguous
                   "Not in the supplied spec. Directive type, accepted syntax, requires/excludes and signature are unconfirmed."
                   :args                (unknown-args)}]))
        {:split :transformative
         :div-0 :annotative          ; written pebblestream:div/0 — '/' is not legal in a keyword name here
         :header-count :annotative
         :board-state :annotative :board-feeds :annotative
         :board-retain :annotative :board-status :annotative
}))

(defn- accepts-no-args? [args]
  (m/validate (m/schema args {:registry registry}) []))

(defn- normalize-syntax
  "Drops :bare from a directive that cannot be written without arguments."
  [{:keys [args] :as entry}]
  (cond-> entry
    (not (accepts-no-args? args)) (update :syntax disj :bare)))

(def ^:private deprecated-directives
  "Directives that are deprecated. header-map is not deprecated."
  #{:header-count :group-unique :group-sum :group-ratio :split :div-0})

(def all-directives
  (update-vals
   (reduce (fn [m k] (assoc-in m [k :status] :deprecated))
           (merge (update-vals directives #(assoc % :pebblestream/source :supplied-spec))
                  (update-vals documented-directives #(assoc % :pebblestream/source :documentation))
                  (update-vals connector-directives #(assoc % :pebblestream/source :design))
                  missing-directives)
           deprecated-directives)
   normalize-syntax))

;; ========================================================
;; Execution order
;; ========================================================

(def execution-order
  "PebbleScript is declarative: where a directive is written, and in what order,
   does not affect when it runs. Transformative directives on a worksheet run
   in this fixed order. Each step is a directive, or a directive with the
   directives that run as part of it (:within). The order among the :within
   directives does not matter.

   All transformative directives run before the worksheet's formulas. After
   them, formula columns are filled down to the transformed rows and
   calculated; value-checking annotative directives then run on the completed
   worksheet. So prune, which runs last, can test columns the transformation
   produced (such as a group's total) but not formula columns."
  [{:step 1  :directive :from}
   {:step 2  :directive :cartesian-product}
   {:step 3  :directive :join}
   {:step 4  :directive :outer-join}
   {:step 5  :directive :filter}
   {:step 6  :directive :sort}
   {:step 7  :directive :group
    :within #{:coalesce :sum :ratio :mult :equal :regex}}  ; unordered: their relative order does not matter
   {:step 8  :directive :group-unique :status :deprecated}
   {:step 9  :directive :group-sum    :status :deprecated}
   {:step 10 :directive :group-ratio  :status :deprecated}
   {:step 11 :directive :header-map}
   {:step 12 :directive :split        :status :deprecated}
   {:step 13 :directive :prune}
   ;; Connector verbs take the place of from, so they share its step; their
   ;; sub-directives are part of the request.
   {:step 1 :directive :http  :within #{:method :send :expect :response :poll :callback}}
   {:step 1 :directive :files :within #{:directory :upload :download :poll :remove :rename}}
   {:step 1 :directive :sql   :within #{:select :where :order-by :limit :poll :insert :update :set :call}}
   {:step 1 :directive :queue}
   {:step 1 :directive :email}])

(defn execution-step
  "The step number at which a directive runs, or nil when it is not placed."
  [k]
  (some (fn [{:keys [step directive within]}]
          (when (or (= k directive) (some #{k} within)) step))
        execution-order))

(defn unplaced-transformative
  "Transformative directives with no place in `execution-order` yet."
  [entries]
  (->> entries
       (filter (fn [[k e]] (and (= :transformative (:directive-type e))
                                (not= :disabled (:status e))
                                (nil? (execution-step k)))))
       (map key)
       sort))

;; ========================================================
;; Annotative directives: value checks
;; ========================================================

(def value-checking-annotative
  "Annotative directives that check cell values. They run on the completed
   worksheet, after its transformative directives, so they see the result rows."
  #{:fail :warn :not-blank :unique :allowed-values :sorted :id})

(def formula-evaluated
  "Directives evaluated on the formula cells of the column they annotate, after
   the formulas are filled down to the transformed rows. They never check cells
   produced directly by a transformative directive; to check such a value, test
   it in a formula column and annotate that column."
  #{:fail :warn})

(def formula-evaluated-ambiguity
  (str "Confirmed for fail and warn. Unclear whether stop, not-blank, unique and allowed-values are also "
       "evaluated only on formula cells, or check literal and transformed values too; and whether annotating "
       "a non-formula column with fail or warn is a compile error."))

(def non-value-checking-annotative
  "Annotative directives that do not check cell values. Their order does not matter."
  #{:do-not-compile :out :ignore :hint :header-count
    :template-name :block-id :template-row :template-column :leftmost-column
    :rightmost-column :top-row :bottom-row :block-row-count :row-oriented-headers
    :column-oriented-headers :template-out-header :template-out-cell
    :template-out-down :template-out-right :template-out-group :template-out-style
    :template-out-value-type :template-out-generate-clean})

(def ^:private classify-candidates
  "Annotative directives that must be classified as value-checking or not:
   those in the supplied spec, plus documented ones known to be annotative."
  #{:div-0 :text :number :boolean :date :id})

(defn directive-phase
  "When a directive runs relative to the worksheet's computation:
   {:phase :transformation :step n}, {:phase :value-check},
   {:phase :unordered}, or nil when not yet known."
  [k]
  (cond
    (execution-step k)                           {:phase :transformation :step (execution-step k)}
    (contains? value-checking-annotative k)      {:phase :value-check}
    (contains? non-value-checking-annotative k)  {:phase :unordered}))

(defn unclassified-annotative
  "Annotative directives not yet classified as value-checking or not."
  [entries]
  (->> entries
       (filter (fn [[k e]]
                 (and (= :annotative (:directive-type e))
                      (or (= :supplied-spec (:pebblestream/source e))
                          (contains? classify-candidates k))
                      (not (contains? value-checking-annotative k))
                      (not (contains? non-value-checking-annotative k)))))
       (map key)
       sort))

;; ========================================================
;; Scope
;; ========================================================

(def column-scoped
  "Directives that apply to the column they annotate and may appear on more
   than one column of a worksheet."
  #{:stop :fail :warn :text :number :boolean :date :id :workflow-group :workflow-terminate})

(def sheet-scoped
  "Directives that apply to the whole worksheet. By convention they are
   written in the first column's annotation, though the compiler accepts any."
  #{:from :do-not-compile :workflow-each :workflow-all :workflow-filter
    :http :method :send :expect :response :poll :callback
    :files :directory :upload :download :remove :rename
    :sql :select :where :order-by :limit :insert :update :set :call
    :queue :email})

(defn scope
  "The directive's scope, :column or :sheet, or nil when not yet confirmed."
  [k]
  (cond (contains? column-scoped k) :column
        (contains? sheet-scoped k)  :sheet))

(defn unscoped
  "Directives in the supplied spec whose scope is not yet confirmed."
  [entries]
  (->> entries
       (filter (fn [[k e]] (and (= :supplied-spec (:pebblestream/source e))
                                (not= :disabled (:status e))
                                (nil? (scope k)))))
       (map key)
       sort))

;; ========================================================
;; Worksheet rules
;; ========================================================

(def ^:private group-step-directives
  (:within (first (filter #(= :group (:directive %)) execution-order))))

(defn write-targets
  "The columns a transformative directive writes to. A directive with an
   optional new column writes there when given, otherwise to its source column.
   Cell references are skipped, since their values are unknown until the
   worksheet is compiled."
  [{:keys [directive args]}]
  (->> (case directive
         (:sum :ratio :mult :equal
          :min :max :average)               [(or (second args) (first args))]
         :regex                             [(or (nth args 2 nil) (second args))]
         :balance                           [(nth args 1 nil) (nth args 2 nil)]
         :header-map                        (take-nth 2 (rest args))
         :paths-operation                   [(second args)]
         (:yield :npv)                      [(nth args 4 nil)]
         [])
       (filter string?)))

(defn undeclared-targets
  "The worksheet's headers determine which columns appear after its
   transformation: a source column not declared as a header is left out of the
   result. A column a transformative directive writes to must be declared.
   Given the worksheet's headers and parsed directives, the target columns
   that are not declared."
  [headers worksheet-directives]
  (let [declared (set headers)]
    (for [d      worksheet-directives
          column (write-targets d)
          :when  (not (contains? declared column))]
      {:column column :written-by (:directive d)})))

(def undeclared-targets-ambiguity
  "Assumed that leaving out a target column is a compile error rather than a silent omission.")

(def group-write-ambiguity
  (str "Assumed that sum, ratio, mult and equal write to their source column when no new column is given. "
       "Unconfirmed, as is whether directives that require group but are not listed within the group step "
       "(min, max, average, balance, expand, yield, npv, pivot) follow the same rule."))

(defn group-write-conflicts
  "Given the parsed directives on one worksheet, the columns written by more
   than one directive within the group step. Directives within the group step
   may read the same column but may not write to the same column."
  [worksheet-directives]
  (->> worksheet-directives
       (filter #(contains? group-step-directives (:directive %)))
       (mapcat (fn [d] (map (fn [t] [t (:directive d)]) (write-targets d))))
       (group-by first)
       (keep (fn [[column writers]]
               (when (> (count writers) 1)
                 {:column column :written-by (mapv second writers)})))))

;; ========================================================
;; Schema for a directive entry (validates the registry)
;; ========================================================

(def directive-entry
  [:map {:closed true}
   [:directive-type [:enum :transformative :annotative :generative]]
   [:syntax [:and [:set [:enum :colon :paren :bare]] [:fn {:error/message "must include :paren"} #(contains? % :paren)]]]
   [:requires {:optional true} [:set :keyword]]
   [:requires-any {:optional true} [:set :keyword]]
   [:excludes {:optional true} [:set :keyword]]
   [:status {:optional true} [:enum :active :disabled :deprecated :proposed]]
   [:args :any]
   [:pebblestream/source [:enum :supplied-spec :documentation :design :not-in-supplied-spec]]
   [:pebblestream/ambiguous {:optional true} :string]])

;; ========================================================
;; Schema for a parsed directive
;; ========================================================

(def parsed-directive
  "Validates {:directive k, :syntax :colon|:paren|:bare, :args [...]}.
   :args holds the parsed parameter values in order."
  (m/schema
   (into [:multi {:dispatch :directive}]
         (map (fn [[k {:keys [syntax args]}]]
                (let [m [:map {:closed true}
                         [:directive [:= k]]
                         [:syntax (into [:enum] (sort syntax))]
                         [:args args]]]
                  [k (if (contains? syntax :bare)
                       [:and m [:fn {:error/message "the bare form takes no arguments"
                                     :error/path    [:args]}
                                #(or (not= :bare (:syntax %)) (empty? (:args %)))]]
                       m)])))
         (sort-by key all-directives))
   {:registry registry}))

;; ========================================================
;; Syntax ambiguities
;; ========================================================

(def syntax-ambiguities
  "Ambiguities about surface syntax that no single schema can carry."
  [{:where  [:syntax :cell-references]
    :reason (str "Any argument may be a worksheet-qualified cell reference, written unquoted in text (Inputs!B2). "
                 "Unconfirmed whether a reference may be combined with literals (\">\"&Inputs!B2).")}
   {:where  [:directive :div-0]
    :reason "Written pebblestream:div/0; keyed here as :div-0 because '/' cannot appear in a keyword's name."}
   {:where  [:directive :sheet-role]
    :reason "The supplied spec does not say which sheet roles (input, output) permit each directive."}])

;; ========================================================
;; Checks and reports
;; ========================================================

(defn explain
  "Human-readable errors for a parsed directive, or nil when it is valid."
  [d]
  (some-> (m/explain parsed-directive d) me/humanize))

(defn deprecated-directive?
  "True when a parsed directive names a deprecated directive."
  [d]
  (= :deprecated (get-in all-directives [(:directive d) :status])))

(defn deprecated-syntax?
  "True when a parsed directive uses the deprecated colon form."
  [d]
  (= :colon (:syntax d)))

(defn registry-errors
  "Entries that do not match `directive-entry`."
  []
  (into {}
        (keep (fn [[k e]]
                (when-let [err (m/explain directive-entry e)]
                  [k (me/humanize err)])))
        all-directives))

(defn undefined-references
  "Directives named in :requires or :excludes that are not defined."
  []
  (for [[k e] all-directives
        rel   [:requires :excludes]
        ref   (get e rel)
        :when (not (contains? all-directives ref))]
    {:directive k :relation rel :undefined ref}))

(declare asymmetric-excludes)

(defn excludes-errors
  "Every exclusion must be written on both directives. Returns the one-way
   pairs as errors; empty when the registry is consistent."
  []
  (for [[a b] (asymmetric-excludes)]
    {:directive b :error (str "excludes must list " (name a) ", since " (name a) " excludes " (name b))}))

(defn asymmetric-excludes
  "Pairs where A excludes B but B does not exclude A."
  []
  (for [[a e] all-directives
        b     (:excludes e)
        :when (contains? all-directives b)
        :when (not (contains? (get-in all-directives [b :excludes]) a))]
    [a b]))

(defn excludes-closure
  "Directive -> excludes, made symmetric."
  []
  (reduce (fn [acc [a b]] (update acc b (fnil conj #{}) a))
          (update-vals all-directives #(or (:excludes %) #{}))
          (for [[a e] all-directives b (:excludes e)] [a b])))

(defn ambiguities
  "Every :pebblestream/ambiguous marker, in parameter types and directives."
  []
  (concat
   (for [[t s] (sort-by key param-registry)
         :let  [reason (:pebblestream/ambiguous (m/properties (m/schema s {:registry registry})))]
         :when reason]
     {:where [:param-type t] :reason reason})
   (for [[k e] (sort-by key all-directives)
         :when (:pebblestream/ambiguous e)
         :when (not= :deprecated (:status e))]   ; deprecated directives need no further definition
     {:where [:directive k] :reason (:pebblestream/ambiguous e)})
   (for [{:keys [step directive] :as s} execution-order
         :when (:pebblestream/ambiguous s)]
     {:where [:execution-order step directive] :reason (:pebblestream/ambiguous s)})
   [{:where [:worksheet-rule :group-write-targets] :reason group-write-ambiguity}
    {:where [:value-check :formula-cells] :reason formula-evaluated-ambiguity}
    {:where [:worksheet-rule :declared-targets] :reason undeclared-targets-ambiguity}
    {:where  [:scope]
     :reason (str "Scope (worksheet or column, and whether it may appear on more than one column) "
                  "not yet confirmed for: "
                  (clojure.string/join ", " (map name (unscoped all-directives))) ".")}]
   (for [k (unclassified-annotative all-directives)]
     {:where  [:value-check k]
      :reason (str "Not yet classified: if this directive checks cell values it runs on the completed worksheet; "
                   "if not, its order does not matter.")})
   (for [k (unplaced-transformative all-directives)]
     {:where  [:execution-order k]
      :reason (str "Transformative directive with no confirmed place in the execution order"
                   (when-let [r (seq (get-in all-directives [k :requires]))]
                     (str "; it requires " (clojure.string/join ", " (map name r))
                          (when (some #{:group} r) ", so it likely runs within the group step")))
                   ".")})
   (let [param-forms (set (map #(m/form (m/schema % {:registry registry})) (vals param-registry)))]
     (for [[k e] (sort-by key all-directives)
           {:keys [path schema]} (mu/subschemas (m/schema (:args e) {:registry registry}))
           :let  [reason (:pebblestream/ambiguous (m/properties schema))]
           :when (and reason
                      (not (contains? param-forms (m/form schema)))   ; reported under :param-type
                      (not (contains? missing-directives k)))]       ; reported under :directive
       {:where [:directive k :args path] :reason reason}))
   syntax-ambiguities))
