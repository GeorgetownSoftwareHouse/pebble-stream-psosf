# Changelog

## 0.1.0

- Read, write and classify PSOSF archives on the JVM and in JavaScript.
- Three levels: invalid, valid (well-formed and convertible to Excel), acceptable (passes the Pebble Stream checks that need no compilation).
- Hostile-input archive reading, field escaping, canonical writing, version 1.0 reading.
- `psosf.model`: pure functions to build, traverse and edit archives as Clojure data.
- `psosf.api`, a JSON-shaped contract for language adapters; a Java entry point; an npm package with TypeScript types.
- The API contract as JSON Schema (version 1), and a language-neutral conformance suite of 34 cases.
- Worksheet rules: `pebblestream.directives.schema/spreadsheet-errors` checks repeated directives, join with outer-join, header arguments against actual headers, formulas in transformed columns, cycles formed by directives or formula references, `ignore` on connected worksheets, `blank` in `allowed-values`, and suggests replacements for deprecated `group-*` directives.
- `pebblestream.directives.check` and `bb check`: one report of structural, directive and worksheet-rule errors for an archive, as JSON, with fixes; a PebbleScript directive-line parser (`pebblestream.directives.parse`).
- `@pebblestream/psosf-directives`: the check and the directive parser as an npm package for Node and the browser, with TypeScript types.
- `psosf-directives`: the PebbleScript directive registry and the directive string parser.
