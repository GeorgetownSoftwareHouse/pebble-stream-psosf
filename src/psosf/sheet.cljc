;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.sheet
  "Worksheet files: a four-line preamble (annotations, types, styles, headers)
   followed by one line per worksheet row.

   A parsed worksheet:
     {:name    \"Accounts\"
      :columns [{:header \"amount\" :type \"number\"
                 :annotation [{:kind :directive :text \"pebblestream:fail\"}
                              {:kind :comment   :text \"Stops the run ...\"}]
                 :style  nil}]           ; or the line 3 style map
      :rows    [[{:kind :text :text \"1\"} {:kind :formula :text \"=C2<=0\"} ...]]}

   An empty cell is {:kind :text :text \"\"}. Values stay text: interpreting them
   by column type is left to the consumer."
  (:require [clojure.string :as string]
            [psosf.csv :as csv]
            [psosf.field :as field]
            [psosf.json :as json]))

(def types #{"text" "number" "boolean" "date"})

(def directive-prefix "pebblestream:")

(defn annotation-lines
  "Splits annotation text into directive and comment lines, keeping order."
  [text]
  (if (= "" text)
    []
    (mapv (fn [line]
            {:kind (if (string/starts-with? (string/triml line) directive-prefix) :directive :comment)
             :text line})
          (string/split text #"\n" -1))))

(defn annotation-text [lines] (string/join "\n" (map :text lines)))

(defn- err [file line column code message & [fix]]
  (cond-> {:code code :file file :line line :message message}
    column (assoc :column column)
    fix    (assoc :fix fix)))

(def ^:private style-keys #{"id" "name" "palette-id" "style"})

(defn- read-style [text]
  (when-not (= "" text)
    (let [v (json/read-str text)]
      (when-not (and (map? v) (map? (get v "style")) (every? style-keys (keys v)))
        (throw (ex-info "bad style" {})))
      v)))

(defn parse
  "Parses a worksheet file. Returns {:sheet parsed :errors [...]}; :sheet is nil
   when the file cannot be read far enough to build one. Pass {:legacy? true}
   for a version 1.0 archive."
  ([file-name text] (parse file-name text {}))
  ([file-name text {:keys [legacy?]}]
  (let [records (try (csv/read-records text)
                     (catch #?(:clj Exception :cljs :default) e
                       {:csv-error (ex-message e)}))]
    (if (map? records)
      {:errors [(err file-name nil nil :bad-csv (str "The file is not valid CSV: " (:csv-error records)))]}
      (let [sheet-name (string/replace file-name #"\.csv$" "")]
        (if (< (count records) 4)
          {:errors [(err file-name nil nil :missing-preamble
                         "A worksheet file needs four preamble lines: annotations, types, formatting and headers.")]}
          (let [[ann-line type-line style-line header-line & data] records
                width     (count header-line)
                width-errs (keep-indexed
                            (fn [i rec]
                              (when (not= width (count rec))
                                (err file-name (inc i) nil :field-count
                                     (str "Line " (inc i) " has " (count rec) " fields; the headers have " width "."))))
                            records)
                ;; read every field, collecting escaping errors
                read-line* (fn [line-no rec cell?]
                             (map-indexed
                              (fn [col f]
                                (let [r      (field/read-field f {:cell? cell? :legacy? legacy?})
                                      header (nth header-line col nil)]
                                  (if (:error r)
                                    {:error (err file-name line-no header (:error r) (:message r) (:fix r))}
                                    r)))
                              rec))
                ann     (read-line* 1 ann-line false)
                typ     (read-line* 2 type-line false)
                sty     (read-line* 3 style-line false)
                hdr     (read-line* 4 header-line false)
                rows    (map-indexed (fn [i rec] (vec (read-line* (+ 5 i) rec true))) data)
                field-errs (keep :error (concat ann typ sty hdr (apply concat rows)))
                text-of #(if (:error %) "" (:text %))
                headers (mapv text-of hdr)
                header-errs (concat
                             (keep-indexed (fn [i h] (when (string/blank? h)
                                                       (err file-name 4 nil :blank-header (str "Column " (inc i) " has no header."))))
                                           headers)
                             (for [[h n] (frequencies (remove string/blank? headers)) :when (> n 1)]
                               (err file-name 4 h :duplicate-header (str "The header \"" h "\" appears " n " times."))))
                type-errs (keep-indexed (fn [i t]
                                          (let [t (text-of t)]
                                            (when-not (contains? types t)
                                              (err file-name 2 (nth headers i nil) :bad-type
                                                   (str "\"" t "\" is not a type; use text, number, boolean or date.")))))
                                        typ)
                styles  (map-indexed (fn [i s]
                                       (try {:style (read-style (text-of s))}
                                            (catch #?(:clj Exception :cljs :default) _
                                              {:error (err file-name 3 (nth headers i nil) :bad-style
                                                           "A style must be a JSON object with a \"style\" object and only id, name, palette-id and style keys.")})))
                                     sty)
                style-errs (keep :error styles)
                columns (mapv (fn [i]
                                {:header     (nth headers i "")
                                 :type       (text-of (nth typ i {:text ""}))
                                 :annotation (annotation-lines (text-of (nth ann i {:text ""})))
                                 :style      (:style (nth styles i {}))})
                              (range width))]
            {:sheet  {:name sheet-name :columns columns :rows (mapv #(mapv (fn [c] (if (:error c) {:kind :text :text ""} c)) %) rows)}
             :errors (vec (concat width-errs field-errs header-errs type-errs style-errs))})))))))

(defn write
  "Writes a parsed worksheet back to file text."
  [{:keys [columns rows]}]
  (csv/write-records
   (concat
    [(mapv #(field/write-text (annotation-text (:annotation %))) columns)
     (mapv #(field/write-text (:type %)) columns)
     (mapv #(if (:style %) (field/write-text (json/write-compact (:style %))) "") columns)
     (mapv #(field/write-text (:header %)) columns)]
    (map #(mapv field/write-content %) rows))))
