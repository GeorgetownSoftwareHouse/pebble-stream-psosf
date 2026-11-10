;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.js
  "The JavaScript adapter: psosf.api with plain JavaScript objects, plus ZIP
   reading and writing with fflate. Compiled to the npm package."
  (:refer-clojure :exclude [read])
  (:require [psosf.api :as api]
            [psosf.archive :as archive]
            ["fflate" :as fflate]))

(defn- ->clj [x] (js->clj x))
(defn- ->js [x] (clj->js x))

(defn classify
  "classify(entries, options?) -> result"
  ([entries] (classify entries #js {}))
  ([entries options] (->js (api/classify (->clj entries) (->clj (or options #js {}))))))

(defn read
  "read(entries) -> {archive, errors, warnings}"
  [entries]
  (->js (api/read (->clj entries))))

(defn write
  "write(archive) -> entries"
  [archive]
  (->js (api/write (->clj archive))))

(defn- err [code path message]
  (cond-> {"code" code "message" message} path (assoc "file" path)))

(defn unzip
  "unzip(bytes: Uint8Array, limits?) -> {entries, errors}

   Reads an archive into an entries object without touching the file system.
   Entry count, entry size, total size and compression ratio are checked from
   the ZIP headers before an entry is decompressed, and decompressed sizes are
   checked again afterwards."
  ([bytes] (unzip bytes #js {}))
  ([bytes limits]
   (let [{:keys [max-entries max-entry-bytes max-total-bytes max-ratio]}
         (merge archive/default-limits
                (let [l (js->clj limits)]
                  (cond-> {}
                    (get l "maxEntries")    (assoc :max-entries (get l "maxEntries"))
                    (get l "maxEntryBytes") (assoc :max-entry-bytes (get l "maxEntryBytes"))
                    (get l "maxTotalBytes") (assoc :max-total-bytes (get l "maxTotalBytes"))
                    (get l "maxRatio")      (assoc :max-ratio (get l "maxRatio")))))
         seen   (atom [])
         total  (atom 0)
         errors (atom [])
         files  (try
                  (fflate/unzipSync
                   bytes
                   #js {:filter
                        (fn [f]
                          (let [n (.-name f) size (.-originalSize f) csize (max 1 (.-size f))]
                            (swap! seen conj n)
                            (swap! total + size)
                            (cond
                              (> (count @seen) max-entries)
                              (do (swap! errors conj (err "too-many-entries" nil (str "The archive has more than " max-entries " entries."))) false)
                              (> size max-entry-bytes)
                              (do (swap! errors conj (err "entry-too-large" n "The entry exceeds the size limit.")) false)
                              (> @total max-total-bytes)
                              (do (swap! errors conj (err "archive-too-large" n "The archive exceeds the total size limit.")) false)
                              (> (/ size csize) max-ratio)
                              (do (swap! errors conj (err "compression-ratio" n "The entry exceeds the compression ratio limit.")) false)
                              (.endsWith n "/") false
                              :else true)))})
                  (catch :default e
                    (swap! errors conj (err "bad-zip" nil (str "Not a readable ZIP archive: " (.-message e))))
                    #js {}))
         decoder (js/TextDecoder. "utf-8" #js {:fatal true})
         entries (reduce
                  (fn [acc n]
                    (let [data (aget files n)]
                      (cond
                        (> (.-length data) max-entry-bytes)
                        (do (swap! errors conj (err "entry-too-large" n "The entry exceeds the size limit.")) acc)
                        :else
                        (try (assoc acc n (.decode decoder data))
                             (catch :default _
                               (swap! errors conj (err "bad-encoding" n "The entry is not valid UTF-8."))
                               acc)))))
                  {}
                  (js-keys files))
         dups (for [[n c] (frequencies @seen) :when (> c 1)]
                (err "duplicate-entry" n (str "The entry appears " c " times.")))]
     (->js {"entries" entries "errors" (into @errors dups)}))))

(defn zip
  "zip(entries) -> Uint8Array, entries written in path order."
  [entries]
  (let [m (js->clj entries)
        o #js {}]
    (doseq [[p text] (sort-by key m)]
      (aset o p (fflate/strToU8 text)))
    (fflate/zipSync o)))

(defn classify-zip
  "classifyZip(bytes, options?) -> result. Unzips, then classifies."
  ([bytes] (classify-zip bytes #js {}))
  ([bytes options]
   (let [u (unzip bytes)]
     (if (pos? (.-length (.-errors u)))
       (->js {"level" "invalid" "errors" (js->clj (.-errors u)) "warnings" [] "acceptance-errors" []})
       (classify (.-entries u) options)))))
