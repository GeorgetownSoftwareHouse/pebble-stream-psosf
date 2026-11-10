;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns runner
  "Runs JVM test namespaces and exits non-zero on failure.
   clojure -M:test [namespace ...]   or   bb test:jvm"
  (:require [clojure.test :as t]))

(defn -main [& namespaces]
  (let [nss (map symbol (or (seq namespaces) ["psosf.core-test"]))]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
