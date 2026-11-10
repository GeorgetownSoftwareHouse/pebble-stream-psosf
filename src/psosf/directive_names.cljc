;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

;; GENERATED from the Pebble Stream directive registry. Do not edit by hand.
(ns psosf.directive-names
  "Every PebbleScript directive name, with its status:
     :active      runs today
     :deprecated  still runs; avoid in new worksheets
     :disabled    recognized but not available
     :proposed    specified but not yet available
     :reserved    the name is taken; its definition is not yet published")

(def directives
  {
   "allowed-values" :active
   "apportion" :active
   "average" :active
   "balance" :active
   "block-id" :active
   "block-row-count" :active
   "board-feeds" :reserved
   "board-retain" :reserved
   "board-state" :reserved
   "board-status" :reserved
   "boolean" :active
   "bottom-row" :active
   "call" :proposed
   "callback" :proposed
   "cartesian-product" :active
   "coalesce" :active
   "column-oriented-headers" :active
   "date" :active
   "directory" :proposed
   "div/0" :deprecated
   "do-not-compile" :active
   "download" :proposed
   "email" :proposed
   "equal" :active
   "expand" :active
   "expect" :proposed
   "fail" :active
   "files" :proposed
   "filter" :active
   "from" :active
   "generate-rows" :active
   "group" :active
   "group-ratio" :deprecated
   "group-sum" :deprecated
   "group-unique" :deprecated
   "grouped" :active
   "header-count" :deprecated
   "header-map" :active
   "hint" :active
   "http" :proposed
   "id" :active
   "identical-columns" :active
   "ignore" :active
   "insert" :proposed
   "join" :active
   "leftmost-column" :active
   "limit" :proposed
   "match-rows" :active
   "max" :active
   "method" :proposed
   "min" :active
   "mult" :active
   "not-blank" :active
   "npv" :active
   "number" :active
   "order-by" :proposed
   "out" :active
   "outer-join" :active
   "paths" :active
   "paths-operation" :active
   "pivot" :active
   "poll" :proposed
   "prune" :active
   "queue" :proposed
   "ratio" :active
   "regex" :disabled
   "relieve" :active
   "remove" :proposed
   "rename" :proposed
   "response" :proposed
   "rightmost-column" :active
   "row-oriented-headers" :active
   "select" :proposed
   "send" :proposed
   "set" :proposed
   "sort" :active
   "sorted" :active
   "split" :deprecated
   "sql" :proposed
   "stop" :active
   "sum" :active
   "template-column" :active
   "template-name" :active
   "template-out-cell" :active
   "template-out-down" :active
   "template-out-generate-clean" :active
   "template-out-group" :active
   "template-out-header" :active
   "template-out-right" :active
   "template-out-style" :active
   "template-out-value-type" :active
   "template-row" :active
   "text" :active
   "top-row" :active
   "unique" :active
   "update" :proposed
   "upload" :proposed
   "warn" :active
   "where" :proposed
   "workflow-all" :active
   "workflow-each" :active
   "workflow-filter" :active
   "workflow-group" :active
   "workflow-iterate" :active
   "workflow-terminate" :active
   "yield" :active
   })
