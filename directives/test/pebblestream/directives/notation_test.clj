;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.notation-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pebblestream.directives.notation :as n]
            [pebblestream.directives.schema :as schema]
            [pebblestream.directives.text :as text]))

;; ========================================================
;; Examples from the published specification
;; ========================================================

(def ^:private spec-file
  (some #(when (.exists (io/file %)) %)
        ["docs/pebblescript-json-notation.md" "../docs/pebblescript-json-notation.md"]))

(defn- code-blocks
  "Fenced code blocks in order, as {:lang \"json\"|\"\" :body \"...\"}."
  [md]
  (for [[_ lang body] (re-seq #"(?ms)^```(\w*)\n(.*?)^```" md)]
    {:lang lang :body body}))

(defn- example-pairs
  "Each plain block immediately followed by a json block: the spec's text and
   JSON Notation forms of the same directives."
  []
  (->> (code-blocks (slurp spec-file))
       (partition 2 1)
       (filter (fn [[a b]] (and (= "" (:lang a)) (= "json" (:lang b))
                                (string/includes? (:body a) "pebblestream:"))))
       (map (fn [[a b]]
              {:text (remove string/blank? (string/split-lines (:body a)))
               :json (vec (json/parsed-seq (java.io.StringReader. (:body b))))}))))

(deftest spec-examples
  (let [pairs (example-pairs)]
    (is (< 30 (count pairs)) "the spec's examples were found")
    (doseq [{:keys [text json]} pairs]
      (if (and (= 1 (count json)) (vector? (first json)))
        ;; An annotation: directive and comment lines, and one JSON array.
        (let [items (first json)]
          (testing (str "annotation " (pr-str text))
            (is (= items (:value (n/annotation->json text))))
            (is (= (string/join "\n" text) (:value (n/json->annotation items))))))
        (do
          (is (= (count text) (count json)) (str "one JSON object per line: " (pr-str text)))
          (doseq [[line m] (map vector text json)]
            (testing line
              (is (= m (:value (n/text->json line))) "text -> JSON matches the spec")
              (is (= line (:value (n/json->text m))) "JSON -> text gives the spec's text"))))))))

;; ========================================================
;; The notation covers the registry
;; ========================================================

(deftest registry-coverage
  (testing "every registry directive has a notation or a stated reason"
    (doseq [k (keys schema/all-directives)]
      (is (or (contains? n/notation k) (n/no-notation-reason k))
          (str k " has neither a JSON Notation nor a reason for having none"))))
  (testing "every notation entry is a registry directive"
    (doseq [k (keys n/notation)]
      (is (contains? schema/all-directives k) (str k " is not in the registry")))))

;; ========================================================
;; Round trips
;; ========================================================

(def ^:private char-pool
  "Characters that stress quoting and splitting: quotes, backslashes,
   separators, the predicate &, sort-suffix colons, sheet-name apostrophes,
   spaces, a non-breaking space and non-ASCII text."
  (into (mapv str "abcXYZ019 _-.!$\"\\,()&:'\u00A0\té中\u201C\u201D\u2018\u2019") ["🙂"]))

(def ^:private gen-text
  (gen/frequency
   [[8 (gen/fmap string/join (gen/vector (gen/elements char-pool) 0 12))]
    [1 (gen/return "pebblestream:from(\"x\")")]
    [1 (gen/return "\"<=\"&\"Balance\"")]
    [1 (gen/return "Date:desc")]]))

(def ^:private gen-sheet
  (gen/such-that #(re-matches #"^[^\\/?*\[\]:]{1,31}$" %)
                 (gen/fmap string/join (gen/vector (gen/elements (remove #{":" "\\"} char-pool)) 1 31))
                 100))

(def ^:private gen-ref
  (gen/fmap (fn [[sheet q? abs-c col abs-r row]]
              {"ref" (str (if q? (str "'" (string/replace sheet "'" "''") "'!")
                              (str "S" (string/replace sheet #"[^A-Za-z0-9_.]" "") "!"))
                          (when abs-c "$") col (when abs-r "$") row)})
            (gen/tuple gen-sheet gen/boolean gen/boolean
                       (gen/fmap string/join (gen/vector (gen/elements "ABCXYZ") 1 3))
                       gen/boolean (gen/large-integer* {:min 1 :max 1048576}))))

(def ^:private numeric-keys #{"count" "max-rows" "iterations" "max-iterations" "priority"})
(def ^:private sheet-keys   #{"sheet" "sheets"})

(defn- gen-value [key]
  (gen/frequency
   [[9 (cond
         (numeric-keys key) (gen/large-integer* {:min 0 :max 100000})
         (sheet-keys key)   gen-sheet
         (= "values" key)   (gen/one-of [gen-text (gen/large-integer* {:min -1000 :max 1000})])
         :else              gen-text)]
    [1 gen-ref]]))

(def ^:private gen-predicate
  (gen/one-of [(gen-value "predicate")
               (gen/hash-map "operator" (gen/elements (vec text/operators)) "column" gen-text)]))

(defn- gen-object [fields]
  (apply gen/hash-map (mapcat (fn [f] [f (gen-value f)]) fields)))

(defn- gen-directive
  "A valid JSON Notation directive for k."
  [k {:keys [shape required optional key prefix fields] :as spec}]
  (let [base {"directive" (name k)}]
    (case shape
      :fixed
      (gen/fmap (fn [[req opt]] (merge base req opt))
                (gen/tuple (gen-object required)
                           (gen/bind (gen/choose 0 (count optional))
                                     #(gen-object (take % optional)))))
      :many
      (gen/fmap (fn [vs] (if (empty? vs) base (assoc base key vs)))
                (gen/vector (gen-value key) (if (:optional spec) 0 1) 4))
      :pairs
      (gen/fmap (fn [[pre items]] (cond-> (merge base pre) (seq items) (assoc key items)))
                (gen/tuple (gen-object prefix)
                           (gen/vector (gen-object fields) (if (:optional spec) 0 1) 3)))
      :conditions
      (gen/one-of
       [(gen/fmap #(assoc base "conditions" [{"column" %}]) (gen-value "column"))
        (gen/fmap #(assoc base "conditions" %)
                  (gen/vector (gen/hash-map "column" (gen-value "column") "predicate" gen-predicate) 1 4))])
      :sort-columns
      (gen/fmap #(assoc base "columns" %)
                (gen/vector (gen/one-of [(gen/hash-map "column" (gen-value "column"))
                                         (gen/hash-map "column" (gen-value "column")
                                                       "order" (gen/elements ["asc" "desc"]))])
                            1 4))
      :sorted
      (gen/fmap (fn [[o p]] (cond-> base o (assoc "order" o) p (assoc "priority" p)))
                (gen/tuple (gen/one-of [(gen/return nil) (gen/elements ["asc" "desc"])])
                           (gen/one-of [(gen/return nil) (gen/large-integer* {:min 0 :max 99})]))))))

(def ^:private gen-any-directive
  (gen/bind (gen/elements (sort-by key n/notation))
            (fn [[k spec]] (gen-directive k spec))))

(deftest json-text-json-round-trip
  (let [result (tc/quick-check
                3000
                (prop/for-all [m gen-any-directive]
                  (let [t (n/json->text m)]
                    (and (empty? (:errors t))
                         (= m (:value (n/text->json (:value t))))))))]
    (is (:pass? result) (pr-str (get-in result [:shrunk :smallest])))))

(deftest text-is-canonical
  (testing "text -> JSON -> text is stable after one normalization"
    (doseq [[line canonical]
            [["pebblestream:allowed-values:individual,corporate"
              "pebblestream:allowed-values(\"individual\", \"corporate\")"]
             ["pebblestream:from(Accounts)" "pebblestream:from(\"Accounts\")"]
             ["pebblestream:unique()" "pebblestream:unique"]
             ["  pebblestream:sort( Last , \"Date\":desc )" "pebblestream:sort(\"Last\", \"Date\":desc)"]
             ["pebblestream:filter(\"Product\", \"12\\\" pipe\")" "pebblestream:filter(\"Product\", \"12\"\" pipe\")"]
             ["pebblestream:sorted:desc:2" "pebblestream:sorted(\"desc\", 2)"]]]
      (let [once (:value (n/json->text (:value (n/text->json line))))]
        (is (= canonical once) line)
        (is (= once (:value (n/json->text (:value (n/text->json once)))))))))
  (testing "the annotation round trip keeps comments, blank lines and order"
    (let [note "Totals by type.\n\npebblestream:from(\"Accounts\")\n  indented comment\npebblestream:group(\"type\")"]
      (is (= note (:value (n/json->annotation (:value (n/annotation->json note)))))))))

;; ========================================================
;; Errors and warnings
;; ========================================================

(defn- codes [r] (set (map :code (:errors r))))
(defn- warning-codes [r] (set (map :code (:warnings r))))

(declare are-errors)

(deftest text-errors
  (are-errors
   [["pebblestream:from(\"Accounts\"" :invalid-syntax]
    ["pebblestream:from(\"Accounts)" :unterminated-string]
    ["pebblestream:from(Fund Accounts)" :unquoted-space]
    ["pebblestream:from(\"a\",,\"b\")" :empty-argument]
    ["pebblestream:filter(\"a\", \"<=\"&)" :invalid-predicate]
    ["pebblestream:filter(\"a\", \"~\"&\"b\")" :invalid-predicate]
    ["pebblestream:from(\"a\" junk)" :unexpected-text]
    ["pebblestream:nonsense(\"a\")" :signature]
    ["pebblestream:regex(\"a\", \"b\")" :no-notation]
    ["pebblestream:from" :signature]
    ["pebblestream:from:Accounts" :signature]
    ["pebblestream:generate-rows(\"12\")" :signature]
    ["Totals by type." :not-a-directive]]))

(defn- are-errors [cases]
  (doseq [[line code] cases]
    (is (contains? (codes (n/text->json line)) code) (str line " -> " code))))

(deftest json-errors
  (doseq [[m code]
          [[{"directive" "sum" "column" "amount" "bogus" 1} :unknown-key]
           [{"directive" "sum"} :missing-key]
           [{"directive" "sum" "column" nil} :null]
           [{"directive" "from" "sheets" []} :missing-argument]
           [{"directive" "from" "sheets" "Accounts"} :invalid-value]
           [{"directive" "from" "sheets" ["a\nb"]} :line-break]
           [{"directive" "join" "sheet" "R" "on" [{"column" "id"}]} :missing-key]
           [{"directive" "filter" "conditions" [{"column" "a"} {"column" "b" "predicate" "x"}]} :missing-key]
           [{"directive" "filter" "conditions" [{"column" "a" "predicate" {"operator" "~" "column" "b"}}]}
            :invalid-predicate]
           [{"directive" "sort" "columns" [{"column" "a" "order" "up"}]} :invalid-value]
           [{"directive" "generate-rows" "count" {"ref" "not a ref"}} :invalid-ref]
           [{"directive" "generate-rows" "count" "12"} :signature]
           [{"directive" "pebblestream:from" "sheets" ["a"]} :invalid-name]
           [{"directive" "regex"} :no-notation]
           [{"directive" "nonsense"} :unknown-directive]
           [{"sheets" ["a"]} :missing-key]]]
    (is (contains? (codes (n/json->text m)) code) (str (pr-str m) " -> " code))))

(deftest annotation-errors
  (is (contains? (codes (n/json->annotation [{"comment" "two\nlines"}])) :line-break))
  (is (contains? (codes (n/json->annotation [{"comment" "  pebblestream:from(\"a\")"}])) :comment-is-directive))
  (is (= 2 (-> (n/annotation->json "ok\npebblestream:from(") :errors first :line))
      "errors name the line they come from"))

(deftest warnings
  (is (contains? (warning-codes (n/text->json "pebblestream:allowed-values:a,b")) :deprecated-syntax))
  (is (contains? (warning-codes (n/text->json "pebblestream:group-sum(\"fund\")")) :deprecated-directive))
  (is (contains? (warning-codes (n/text->json "pebblestream:from(\"C:\\data\")")) :ambiguous-backslash)))

(deftest references-are-worksheet-qualified
  (testing "an unqualified address is a name"
    (is (= {"directive" "group" "columns" ["ID5"]}
           (:value (n/text->json "pebblestream:group(ID5)"))))
    (is (= {"directive" "from" "sheets" ["Q1"]}
           (:value (n/text->json "pebblestream:from(Q1)")))))
  (testing "a qualified address is a reference"
    (is (= {"directive" "group" "columns" [{"ref" "Setup!ID5"}]}
           (:value (n/text->json "pebblestream:group(Setup!ID5)"))))
    (is (= {"directive" "sort" "columns" [{"column" {"ref" "'Run Settings'!$B$1"} "order" "desc"}]}
           (:value (n/text->json "pebblestream:sort('Run Settings'!$B$1:desc)")))))
  (testing "an unqualified {\"ref\": ...} is refused"
    (is (contains? (codes (n/json->text {"directive" "generate-rows" "count" {"ref" "B1"}})) :invalid-ref))
    (is (contains? (codes (n/json->text {"directive" "generate-rows" "count" {"ref" "$B$1"}})) :invalid-ref))))

;; ========================================================
;; The allowed-values example workbook
;; ========================================================
;;
;; examples/Allowed-Values.xlsx in pebble-stream-documentation. Its notes were
;; typed with typographic quotes, as Excel's notes often are.

(deftest allowed-values-example-workbook
  (testing "column A: 0 up to 43, or 75"
    (is (= {"directive" "allowed-values" "values" ["<43" ">=0"]}
           (:value (n/text->json "pebblestream:allowed-values:\u201D<43\u201D:\u201D>=0\u201D"))))
    (is (= {"directive" "allowed-values" "values" [75]}
           (:value (n/text->json "pebblestream:allowed-values:75")))))
  (testing "column B: antelope up to yak, or aardvark or zebra"
    (is (= {"directive" "allowed-values" "values" ["<yak" ">=antelope"]}
           (:value (n/text->json "pebblestream:allowed-values:\u201D<yak\u201D:\u201D>=antelope\u201D"))))
    (is (= {"directive" "allowed-values" "values" ["aardvark" "zebra"]}
           (:value (n/text->json "pebblestream:allowed-values:\u201Daardvark\u201D:\u201Dzebra\u201D")))))
  (testing "a whole note with a comment line"
    (is (= [{"comment" "mark:"}
            {"directive" "allowed-values" "values" ["<43" ">=0"]}
            {"directive" "allowed-values" "values" [75]}]
           (:value (n/annotation->json
                    "mark:\npebblestream:allowed-values:\u201D<43\u201D:\u201D>=0\u201D\npebblestream:allowed-values:75")))))
  (testing "numbers and text keep their types"
    (is (= "pebblestream:allowed-values(75, \"75\")"
           (:value (n/json->text {"directive" "allowed-values" "values" [75 "75"]}))))))

;; ========================================================
;; Typographic quotes
;; ========================================================

(deftest typographic-quotes
  (testing "curly double quotes delimit strings, and two together are one quote"
    (is (= (:value (n/text->json "pebblestream:filter(\"Product\", \"12\"\" pipe\")"))
           (:value (n/text->json "pebblestream:filter(\u201CProduct\u201D, \u201C12\u201D\u201D pipe\u201D)")))))
  (testing "curly single quotes enclose a reference's worksheet name"
    (is (= {"directive" "generate-rows" "count" {"ref" "'Run Settings'!$B$1"}}
           (:value (n/text->json "pebblestream:generate-rows(\u2018Run Settings\u2019!$B$1)"))))
    (is (= {"directive" "sort" "columns" [{"column" {"ref" "'Run Settings'!B1"} "order" "desc"}]}
           (:value (n/text->json "pebblestream:sort(\u2018Run Settings\u2019!B1:desc)")))))
  (testing "an apostrophe inside a string or name is kept"
    (is (= {"directive" "from" "sheets" ["O\u2019Brien"]}
           (:value (n/text->json "pebblestream:from(\u201CO\u2019Brien\u201D)"))))
    (is (= {"directive" "from" "sheets" ["O\u2019Brien"]}
           (:value (n/text->json "pebblestream:from(O\u2019Brien)"))))
    (is (= {"directive" "generate-rows" "count" {"ref" "'O\u2019Brien'!B1"}}
           (:value (n/text->json "pebblestream:generate-rows('O\u2019Brien'!B1)")))))
  (testing "a literal curly double quote is written with a backslash, without a warning"
    (let [m {"directive" "filter" "conditions" [{"column" "Note" "predicate" "Say \u201Chi\u201D"}]}
          t (:value (n/json->text m))]
      (is (= "pebblestream:filter(\"Note\", \"Say \\\u201Chi\\\u201D\")" t))
      (is (= m (:value (n/text->json t))))
      (is (empty? (:warnings (n/text->json t)))))))

(deftest allowed-values-never-mixes-comparisons-and-plain-values
  (testing "a directive of comparisons or of plain values converts"
    (is (= {"directive" "allowed-values" "values" [">=0" "<43"]}
           (:value (n/text->json "pebblestream:allowed-values(\">=0\", \"<43\")"))))
    (is (= {"directive" "allowed-values" "values" ["=75" ">=80"]}
           (:value (n/text->json "pebblestream:allowed-values(\"=75\", \">=80\")"))))
    (is (= {"directive" "allowed-values" "values" [75 "seventy-five"]}
           (:value (n/text->json "pebblestream:allowed-values(75, \"seventy-five\")")))))
  (testing "a directive that mixes them is refused in both directions"
    (doseq [line ["pebblestream:allowed-values(\">80\", 75)"
                  "pebblestream:allowed-values:75:\">80\""
                  "pebblestream:allowed-values(\"=75\", 70)"
                  "pebblestream:allowed-values(\"aardvark\", \"<yak\")"]]
      (is (contains? (codes (n/text->json line)) :signature) line))
    (is (contains? (codes (n/json->text {"directive" "allowed-values" "values" [">70" 75]})) :signature)))
  (testing "a cell reference is resolved at run time and does not count either way"
    (is (empty? (:errors (n/text->json "pebblestream:allowed-values(\">=0\", Limits!B2)"))))))
