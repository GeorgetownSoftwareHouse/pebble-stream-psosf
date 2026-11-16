;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.js
  "The JavaScript adapter for the directive check, compiled to the
   @pebblestream/psosf-directives npm package."
  (:require [pebblestream.directives.check :as check]
            [pebblestream.directives.parse :as parse]
            [psosf.js :as psjs]))

(defn check
  "check(entries, options?) -> report. entries maps archive paths to file text."
  ([entries] (check entries #js {}))
  ([entries options]
   (clj->js (check/check-entries (js->clj entries) (js->clj (or options #js {}))))))

(defn check-zip
  "checkZip(bytes, options?) -> report. Unzips in memory, then checks."
  ([bytes] (check-zip bytes #js {}))
  ([bytes options]
   (let [u (psjs/unzip bytes)]
     (if (pos? (.-length (.-errors u)))
       (clj->js {"passed" false "level" "invalid" "errors" (js->clj (.-errors u))
                 "warnings" [] "acceptance-errors" []})
       (check (.-entries u) options)))))

(defn- arg->json
  "Keywords (flags, sort orders) become their names; maps become objects."
  [a]
  (cond
    (keyword? a) (name a)
    (map? a)     (into {} (for [[k v] a] [(name k) (arg->json v)]))
    :else        a))

(defn parse-line
  "parseLine(text) -> {directive, syntax, args, warnings} or {error, text}."
  [text]
  (let [{:keys [error directive syntax args warnings] :as r} (parse/parse-line text)]
    (clj->js
     (if error
       {"error" error "text" (:text r)}
       {"directive" (name directive)
        "syntax"    (name syntax)
        "args"      (mapv arg->json args)
        "warnings"  (mapv #(into {} (for [[k v] %] [(name k) (arg->json v)])) warnings)}))))
