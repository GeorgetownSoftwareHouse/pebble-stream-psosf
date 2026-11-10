;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

;; Runs the ClojureScript tests under nbb and sets the process exit code.
(ns psosf.js-runner
  (:require [cljs.test :as t]
            [psosf.js-test]
            [psosf.model-test]
            [psosf.conformance-js]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m) (set! (.-exitCode js/process) 1)))

(t/run-tests 'psosf.js-test 'psosf.model-test 'psosf.conformance-js)
