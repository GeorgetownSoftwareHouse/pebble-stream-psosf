;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns pebblestream.directives.cli
  "Command-line check for PSOSF archives, for people, scripts and AI agents.

     bb check account-balances.psosf
     bb check account-balances.psosf --allow SUM,IF,ROUND
     clojure -M -m pebblestream.directives.cli account-balances.psosf

   Prints the report from pebblestream.directives.check as JSON, and exits 0
   when the archive passed, 1 when it did not, 2 on a usage error."
  (:require [clojure.string :as string]
            [pebblestream.directives.check :as check]
            [psosf.archive :as archive]
            [psosf.json :as json]))

(defn- usage []
  (binding [*out* *err*]
    (println "Usage: check FILE.psosf [--allow FUNCTION,FUNCTION,...]"))
  2)

(defn run
  "Checks a file and prints the report. Returns the exit code."
  [args]
  (let [[path & opts] args
        allow (some->> (second (drop-while #(not= "--allow" %) opts))
                       (#(string/split % #",")) (map string/trim) (remove string/blank?) vec)]
    (cond
      (or (nil? path) (= "--help" path)) (usage)
      :else
      (let [{:keys [entries errors]} (archive/read-zip path)
            report (if (seq errors)
                     {"passed" false "level" "invalid"
                      "errors" (mapv #(into {} (for [[k v] % :when v] [(name k) (if (keyword? v) (name v) v)])) errors)
                      "warnings" [] "acceptance-errors" []}
                     (check/check-entries entries (cond-> {} allow (assoc "allowed-functions" allow))))]
        (print (json/write-canonical report))
        (flush)
        (if (get report "passed") 0 1)))))

(defn -main [& args]
  (System/exit (run args)))
