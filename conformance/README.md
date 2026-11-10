# PSOSF conformance suite

Language-neutral test cases that every PSOSF binding must pass. They are how a
Python, C#, Janet, C or JavaScript binding proves it classifies archives
exactly as the reference Clojure implementation does.

Each case's expected result was written by hand from the
[PSOSF specification](../docs/pebble-stream-open-spreadsheet-format.md), not
recorded from the implementation.

## Layout

```
conformance/
├── cases.json                 the list of cases, and the suite version
├── cases/<name>/case.json     one case
├── cases/<name>/archive.psosf the ZIP container, for cases that test one
└── generate.clj               regenerates the cases (bb conformance:generate)
```

## A case

```json
{
  "name": "function-not-allowed",
  "description": "A formula calling a function missing from the allow-list.",
  "input": { "entries": { "manifest.json": "...", "Accounts.csv": "..." } },
  "options": { "allowed-functions": ["SUM"] },
  "expect": {
    "level": "valid",
    "errors": [],
    "warnings": [],
    "acceptance-errors": [
      { "code": "function-not-allowed", "file": "Accounts.csv", "line": 5, "column": "negative" }
    ]
  },
  "round-trip": { "entries": { "...": "..." } }
}
```

`input` holds either `entries` (archive path to file text) or `zip`, the name
of an `archive.psosf` file in the case's folder. `round-trip` is optional.

## Running the suite in a binding

For every name in `cases.json`:

1. **Classify.** Call the binding's classify with `input.entries` and `options`,
   or, for a `zip` input, its classify-from-ZIP with the file's bytes and
   `options`.
2. **Compare the level.** It must equal `expect.level`.
3. **Compare the findings.** For each of `errors`, `warnings` and
   `acceptance-errors`, reduce every finding in the result to its `code`,
   `file`, `line` and `column` (dropping absent keys, `message` and `fix`), and
   compare with `expect` as a multiset: the same findings, the same number of
   times, in any order. Messages and fixes are human text and may change.
4. **Round trip.** If the case has `round-trip`, read `input.entries`, write the
   resulting archive, and require the written entries to equal
   `round-trip.entries` exactly.
5. **Validate the shapes.** Check every result against
   [`schema/psosf-api-v1.schema.json`](../schema/psosf-api-v1.schema.json).

A binding conforms when every case passes.

## Versioning

`cases.json` carries the suite version. New cases may be added within a
version. Changing an existing case's expected result is a change to the
contract, and needs a new version of the suite and of the library.
