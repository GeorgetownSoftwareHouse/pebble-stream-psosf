# Changelog

## Unreleased

- `psosf-directives`: PebbleScript JSON Notation. `pebblestream.directives.notation` converts directive lines and whole header notes between textual PebbleScript and JSON Notation, in both directions, checked against the registry. `pebblestream.directives.text` parses a directive line (bare, parentheses or colon form) into the registry's parsed form and renders canonical text.
- Cell references in directive arguments are always qualified by their worksheet name (`Inputs!B2`). An unqualified address such as `Q1` or `ID5` is a name. Both specifications, the registry's `:ps/cell-ref` and the line parser follow this rule.
- Typographic quotes in textual PebbleScript: `“` and `”` are read as `"`, and `‘` and `’` as `'` where they enclose a reference's worksheet name, so notes typed with automatic smart quotes parse as intended. A literal `“` or `”` in a string is written with a backslash.
- `allowed-values` accepts numbers as well as text. The JSON Notation specification describes its plain values (equality) and comparisons: a directive holds one kind or the other, its comparisons must all hold, the value must equal one of its plain values, and several directives on one column combine with OR. The registry refuses a directive that mixes the two kinds.
- The registry's `id` directive accepts a cell reference as its group name, like every other argument.

## 0.1.0

- Read, write and classify PSOSF archives on the JVM and in JavaScript.
- Three levels: invalid, valid (well-formed and convertible to Excel), acceptable (passes the Pebble Stream checks that need no compilation).
- Hostile-input archive reading, field escaping, canonical writing, version 1.0 reading.
- `psosf.model`: pure functions to build, traverse and edit archives as Clojure data.
- `psosf.api`, a JSON-shaped contract for language adapters; a Java entry point; an npm package with TypeScript types.
- The API contract as JSON Schema (version 1), and a language-neutral conformance suite of 34 cases.
- `psosf-directives`: the PebbleScript directive registry and the directive string parser.
