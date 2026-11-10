;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns psosf.allow-list
  "The Excel functions Pebble Stream accepts in formulas.

   Maintained by Georgetown Software House from the same source Zen and the
   compiler service use. Until it is filled in, acceptance checks report that
   no allow-list is configured; pass :allowed-functions to psosf.core/classify
   to supply one.")

(def functions
  "Upper-case function names, without _xlfn. or _xlws. prefixes."
  #{})
