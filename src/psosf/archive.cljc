;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.archive
  "Archives as hostile input.

   The portable part checks a list of entry names and an entries map
   {path -> text}. Reading the ZIP container itself is platform-specific; on
   the JVM, read-zip reads it from the central directory with caps enforced
   while reading, never extracting to disk."
  (:require [clojure.string :as string]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import [java.util.zip ZipFile ZipOutputStream ZipEntry]
                   [java.io ByteArrayOutputStream File]
                   [java.nio.charset StandardCharsets])))

(def default-limits
  {:max-entries       2000
   :max-entry-bytes   (* 64 1024 1024)
   :max-total-bytes   (* 256 1024 1024)
   :max-ratio         100})

(def ^:private windows-reserved
  #"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?")

(defn- err [code path message] (cond-> {:code code :message message} path (assoc :file path)))

(defn name-errors
  "Errors for entry names alone: traversal, absolute paths, bad characters,
   reserved names, duplicates and case collisions."
  [names]
  (concat
   (mapcat
    (fn [p]
      (let [segments (string/split p #"/" -1)]
        (cond-> []
          (or (string/starts-with? p "/") (re-find #"^[A-Za-z]:" p))
          (conj (err :absolute-path p "Entry paths must be relative."))
          (string/includes? p "\\")
          (conj (err :backslash p "Entry paths may not contain backslashes."))
          (some #{".." "."} segments)
          (conj (err :path-traversal p "Entry paths may not contain . or .. segments."))
          (re-find #"[\x00-\x1f\x7f]" p)
          (conj (err :control-character p "Entry names may not contain control characters."))
          (some #(and (seq %) (not (#{"." ".."} %)) (re-find #"[. ]$" %)) segments)
          (conj (err :bad-name-ending p "A name may not end in a dot or a space."))
          (some #(re-matches windows-reserved %) segments)
          (conj (err :reserved-name p "A name may not be a reserved Windows device name.")))))
    names)
   (for [[p n] (frequencies names) :when (> n 1)]
     (err :duplicate-entry p (str "The entry appears " n " times.")))
   ;; sorted, so the reported entry is the same on every platform
   (for [[_ ps] (group-by string/lower-case (sort (distinct names))) :when (> (count ps) 1)]
     (err :case-collision (first ps) (str "Entries differ only in case: " (string/join ", " ps))))))

(defn allowed-entry?
  "Root .csv worksheet files, manifest.json, signatures.json and the tests tree."
  [p]
  (or (re-matches #"[^/]+\.csv" p)
      (#{"manifest.json" "signatures.json"} p)
      (re-matches #"tests/[^/]+/test\.json" p)
      (re-matches #"tests/[^/]+/(inputs|expected)/[^/]+\.csv" p)))

(defn structure-errors [names]
  (concat
   (name-errors names)
   (for [p names :when (and (not (string/ends-with? p "/")) (not (allowed-entry? p)))]
     (err :unexpected-entry p "This file is not part of a PSOSF archive."))))

#?(:clj
   (defn read-zip
     "Reads a PSOSF archive from a file path. Returns {:entries {path text}
      :errors [...]}. Entries are read from the central directory into memory
      only; caps on count, size, total size and compression ratio are enforced
      while reading."
     ([path] (read-zip path default-limits))
     ([path {:keys [max-entries max-entry-bytes max-total-bytes max-ratio]}]
      (try
        (with-open [zf (ZipFile. (File. (str path)) StandardCharsets/UTF_8)]
          (let [entries (enumeration-seq (.entries zf))]
            (if (> (count entries) max-entries)
              {:errors [(err :too-many-entries nil (str "The archive has more than " max-entries " entries."))]}
              (loop [es entries total 0 out {} errors []]
                (if-let [^ZipEntry e (first es)]
                  (let [p (.getName e)]
                    (if (.isDirectory e)
                      (recur (rest es) total out errors)
                      (let [buf (ByteArrayOutputStream.)
                            chunk (byte-array 8192)
                            csize (max 1 (.getCompressedSize e))
                            [n over] (with-open [in (.getInputStream zf e)]
                                       (loop [n 0]
                                         (let [r (.read in chunk)]
                                           (cond
                                             (neg? r) [n nil]
                                             (> (+ n r) max-entry-bytes) [n :size]
                                             (> (+ total n r) max-total-bytes) [n :total]
                                             (> (/ (+ n r) csize) max-ratio) [n :ratio]
                                             :else (do (.write buf chunk 0 r) (recur (+ n r)))))))]
                        (if over
                          {:entries out
                           :errors (conj errors (err (case over :size :entry-too-large :total :archive-too-large :ratio :compression-ratio)
                                                     p "The archive exceeds a size or compression limit; reading stopped."))}
                          (recur (rest es) (+ total n)
                                 (assoc out p (String. (.toByteArray buf) StandardCharsets/UTF_8))
                                 errors)))))
                  {:entries out :errors errors})))))
        (catch Exception e
          {:errors [(err :bad-zip nil (str "Not a readable ZIP archive: " (.getMessage e)))]})))))

#?(:clj
   (defn write-zip
     "Writes an entries map {path text} to a ZIP file, in path order."
     [path entries]
     (with-open [out (ZipOutputStream. (io/output-stream (str path)) StandardCharsets/UTF_8)]
       (doseq [[p text] (sort-by key entries)]
         (.putNextEntry out (ZipEntry. ^String p))
         (.write out (.getBytes ^String text StandardCharsets/UTF_8))
         (.closeEntry out)))))
