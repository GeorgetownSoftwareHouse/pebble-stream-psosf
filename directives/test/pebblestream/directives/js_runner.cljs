;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

;; Runs the directive tests under ClojureScript (nbb) and sets the exit code.
(ns pebblestream.directives.js-runner
  (:require [cljs.test :as t]
            [pebblestream.directives.js-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m) (set! (.-exitCode js/process) 1)))

(t/run-tests 'pebblestream.directives.js-test)
