# PSOSF for Clojure

Read, write, traverse and classify [Pebble Stream Open Spreadsheet Format](https://docs.pebblestream.com/docs/pebble-stream-open-spreadsheet-format) (PSOSF) archives from Clojure and ClojureScript.

PSOSF is a plain-text form of a spreadsheet: one CSV file per worksheet, plus a manifest, bundled in a ZIP archive. This library lets people, tools and AI agents work with PSOSF archives safely, without Excel and without Pebble Stream.

Licensed under the [Apache License 2.0](LICENSE). Source: [github.com/GeorgetownSoftwareHouse/pebble-stream-psosf](https://github.com/GeorgetownSoftwareHouse/pebble-stream-psosf).

## Installation

Add the library to `deps.edn` as a Git dependency, using the tag and commit of the [latest release](https://github.com/GeorgetownSoftwareHouse/pebble-stream-psosf/releases):

```clojure
{:deps {io.github.GeorgetownSoftwareHouse/pebble-stream-psosf
        {:git/tag "v0.1.0" :git/sha "abc1234"}}}
```

Replace the tag and the short commit SHA with those of the release you want.

## What it does

- **Reads archives as hostile input.** Entries are read into memory, never extracted to disk. Path traversal, absolute paths, reserved names, duplicates, case collisions, oversized entries and ZIP bombs are rejected.
- **Applies field escaping.** Formulas are written `psosf:=A1+3`; text that a spreadsheet application could evaluate gets a leading apostrophe. Unescaped fields are rejected with a precise fix, never repaired.
- **Parses worksheets into data.** Headers, types, styles, cells, and annotations split into PebbleScript directive lines and comment lines.
- **Writes canonical archives.** The same spreadsheet always produces the same bytes.
- **Classifies an archive** at one of three levels.

### Building and traversing in Clojure

`psosf.model` builds, traverses and edits archives as plain Clojure data. Every function is pure, so archives compose with `->` and ordinary sequence functions:

```clojure
(require '[psosf.core :as psosf]
         '[psosf.model :as m])

(def ledger
  (-> (m/archive "Account Balances" {:namespace "com.example.ledger"})
      (m/add-sheet
       (-> (m/sheet "Accounts")
           (m/add-column {:header "id" :type "text"})
           (m/add-column {:header "amount" :type "number"
                          :annotation ["pebblestream:not-blank" "Balance in US dollars."]})
           (m/add-column {:header "negative" :type "boolean"
                          :annotation ["pebblestream:fail"]})
           (m/add-row ["1" "435" (m/formula "=B2<0")])))))

(psosf/write-file "account-balances.psosf" ledger)

;; Traverse
(m/headers (m/find-sheet ledger "Accounts"))     ;=> ["id" "amount" "negative"]
(m/directives (m/find-sheet ledger "Accounts"))  ;=> ({:column "amount" :text "pebblestream:not-blank"} ...)
(m/formulas (m/find-sheet ledger "Accounts"))    ;=> ({:row 0 :column "negative" :text "=B2<0"})
(m/row-values (m/find-sheet ledger "Accounts"))  ;=> ({"id" "1" "amount" "435" "negative" "=B2<0"})
```

Escaping is handled when the archive is written: text such as `=total` or `@SUM(A1)` is protected automatically, and formulas get their `psosf:` prefix. `psosf.api` and its JSON shapes are only needed by adapters for other languages.

### JavaScript and TypeScript

The same library, compiled from ClojureScript, is published to npm with TypeScript types:

```
npm install @pebblestream/psosf
```

```javascript
import { classifyZip, unzip, read, write, zip } from "@pebblestream/psosf";

const result = classifyZip(bytes, { "allowed-functions": ["SUM", "IF"] });
// { level: "acceptable", errors: [], warnings: [], "acceptance-errors": [], archive: {...} }
```

## Levels

| Level | Meaning |
|---|---|
| `:invalid` | Not a well-formed PSOSF archive. `:errors` says why, with file, line, column and a fix. |
| `:valid` | Well-formed: safe to open directly and convertible to Excel. `:acceptance-errors` says why it is not acceptable. |
| `:acceptable` | Also passes every Pebble Stream acceptance check that can be made without compiling: formulas use only supported functions, nothing refers to another workbook or process, and every directive name is known and available. |

`:acceptable` is as far as an archive can be judged without the Pebble Stream compiler service, which has the final say.

## What it does not do

This library never compiles or executes a spreadsheet. Formulas are treated as text: escaped and scanned for the functions they call and the references they make, never parsed into an expression tree or evaluated. PebbleScript directives are checked by name only. Converting to and from Excel is left to tools that have access to Excel, such as Pebble Stream Zen.

## Usage

```clojure
(require '[psosf.core :as psosf])

;; From a file (JVM)
(psosf/classify-file "account-balances.psosf" {:allowed-functions #{"SUM" "IF"}})
;; => {:level :acceptable, :archive {...}, :errors [], :warnings [], :acceptance-errors []}

;; From an entries map {path text} (JVM or ClojureScript)
(psosf/classify {"manifest.json" "..." "Accounts.csv" "..."})

;; Read, change, write
(let [{:keys [archive]} (psosf/read-entries entries)]
  (psosf/write-entries (assoc-in archive [:manifest "description"] "Updated.")))
```

### From other languages

`psosf.api` is the cross-language contract: every function takes and returns JSON-shaped data (string keys, no keywords), and `classify-json`, `read-json` and `write-json` take and return JSON text. Every language adapter is a thin layer over it, so an archive is classified the same way in every language.

### The contract and the conformance suite

[`schema/psosf-api-v1.schema.json`](schema/psosf-api-v1.schema.json) defines, as JSON Schema, every request and result shape a binding accepts and returns. The [conformance suite](conformance/README.md) is a set of language-neutral cases, each an archive with its expected classification, that every binding runs; a binding conforms when it passes them all. Both run in this repository's own tests, on the JVM and in JavaScript.

### Function allow-list

Acceptance needs the list of Excel functions Pebble Stream supports. It lives in `psosf.allow-list/functions`; until that set is populated, pass `:allowed-functions` to `classify`, or a valid archive is reported as `:valid` with `:allow-list-not-configured`.

## Platforms

Everything is portable `.cljc` except reading and writing the ZIP container. On the JVM, `classify-file` and `write-file` handle it with the standard library; in JavaScript, the npm package uses [fflate](https://github.com/101arrowz/fflate). The Clojure library has no dependencies beyond Clojure itself.

## Status

| Area | Status |
|---|---|
| Archive reading and writing, limits | Done |
| Field escaping, worksheets, manifest, tests folder | Done |
| Structural validation and the three levels | Done |
| Signature verification and signing | Planned; archives with `signatures.json` are read, with a warning that signatures were not verified |
| Building, traversing and editing (`psosf.model`) | Done |
| PebbleScript text ⇄ JSON Notation | Planned |
| npm package (JavaScript, TypeScript) | Done |
| Native library and command line (Python, .NET, any language) | Planned |

## Java

The jar includes a small Java class, `com.pebblestream.psosf.Psosf`, so Java code never handles Clojure types. Every method returns JSON text:

```java
import com.pebblestream.psosf.Psosf;
import java.util.List;

String result = Psosf.classifyFile("account-balances.psosf", List.of("SUM", "IF"));
// {"acceptance-errors":[],"archive":{...},"errors":[],"level":"acceptable","warnings":[]}
```

## Checking spreadsheets for errors

`pebblestream.directives.check` checks an archive against everything Pebble Stream can verify without compiling it, and reports every problem in one list, with the worksheet, the column, the directive and, where one is known, a fix:

1. **Structure and acceptance:** escaping, the manifest, the function allow-list, references outside the spreadsheet.
2. **Directive lines:** each one parses, names a known directive, and matches that directive's signature.
3. **Worksheet rules,** as stated in the [PebbleScript Reference](docs/pebblescript-reference.md#rules-every-worksheet-must-follow): repeated directives, `join` with `outer-join`, header arguments against actual headers (units included), formulas in transformed columns, cycles between worksheets, `ignore` on a connected worksheet, `blank` in `allowed-values`, and replacements for deprecated directives.

### From the command line

```
bb check distributions.psosf --allow SUM,IF
```

The report is JSON on standard output. The exit code is 0 when the archive passed, 1 when it did not, and 2 on a usage error, so a script or CI job can act on it directly:

```json
{
  "acceptance-errors": [],
  "errors": [
    {
      "argument": "NII",
      "code": "unknown-header",
      "column": "Fund ID",
      "fix": "Did you mean \"NII ($)\"?",
      "message": "\"NII\" is not a header of the source worksheets. Header arguments must match exactly, including units.",
      "sheet": "Totals"
    },
    {
      "code": "formula-in-transformed-column",
      "column": "NII ($)",
      "fix": "Remove the formula: the directive fills this column. Give a generated column a header no directive fills.",
      "message": "cell 'Totals'!B2 has a formula, but its column is generated by directives.",
      "sheet": "Totals"
    },
    {
      "code": "blank-in-allowed-values",
      "column": "NII ($)",
      "fix": "Remove \"blank\". To require a value, use pebblestream:not-blank.",
      "message": "pebblestream:allowed-values has no keyword for an empty cell.",
      "sheet": "ClassNII"
    }
  ],
  "level": "acceptable",
  "passed": false,
  "warnings": []
}
```

`passed` is true when the archive is well-formed, breaks no rule, and has no acceptance errors other than a missing allow-list. Warnings, such as a deprecated directive or the deprecated colon form, do not fail the check.

### From code

```clojure
(require '[pebblestream.directives.check :as check])

(check/check-entries entries {"allowed-functions" ["SUM" "IF"]})  ; JSON-shaped report
(check/check-json "{\"entries\": {...}, \"options\": {...}}")    ; JSON text in and out
(check/check-archive archive)                                     ; a psosf.core archive, Clojure data
```

### From JavaScript

The same check, compiled from the same ClojureScript, is published as its own npm package, for Node and the browser:

```
npm install @pebblestream/psosf-directives
```

```javascript
import { checkZip, check, parseLine } from "@pebblestream/psosf-directives";

const report = checkZip(bytes, { "allowed-functions": ["SUM", "IF"] });  // the same report as bb check
parseLine('pebblestream:sort("Last", "Date":desc)');
// { directive: "sort", syntax: "paren", args: ["Last", { column: "Date", order: "desc" }], warnings: [] }
```

It is a separate package from `@pebblestream/psosf` because it includes the directive registry and Malli, which make it larger; applications that only read and write archives don't need them.

### For AI agents

An agent writing or editing a spreadsheet can use the check as its feedback loop:

1. Write the archive with `psosf.model` (or as PSOSF files).
2. Run `bb check`, or call `check-json`.
3. If `passed` is false, fix each error. Every error names its `sheet` and `column`, `argument` when a header argument is wrong, and usually a `fix`, such as `Did you mean "NII ($)"?`.
4. Repeat until `passed` is true, then submit the archive to Pebble Stream, whose compiler has the final say.

`code` values are stable identifiers, safe to match on; `message` and `fix` are written for people and may change.

## Directive library

`com.pebblestream/psosf-directives` is a separate artifact holding the PebbleScript directive registry (a [Malli](https://github.com/metosin/malli) schema of every directive's signature, exclusions, execution order and status) and the directive string parser. The core library does not depend on it.

## Repository layout

```
pebble-stream-psosf/
├── src/psosf/                 the psosf library (.cljc, plus js.cljs for npm)
├── src/java/                  the Java entry point, compiled into the jar
├── test/                      JVM and ClojureScript tests, and the test runners
├── directives/
│   ├── src/pebblestream/      the directive registry, parsers and the check
│   ├── test/                  their tests
│   └── npm/                   the @pebblestream/psosf-directives package and its types
├── conformance/               language-neutral cases every binding must pass
├── schema/                    the JSON Schema of the API contract, version 1
├── docs/                      the PSOSF specification and the PebbleScript Reference
├── build.clj                  jar builds (tools.build)
├── shadow-cljs.edn            the npm builds
├── package.json, index.d.ts   the npm package and its TypeScript types
├── bb.edn                     development tasks
└── VERSION                    the release version, shared by every artifact
```

## Building and testing

Requires a JDK (11 or later), the [Clojure CLI](https://clojure.org/guides/install_clojure), [babashka](https://babashka.org/) and Node.js.

| Command | Does |
|---|---|
| `bb test` | Every test suite: JVM, directives, and both ClojureScript suites |
| `bb jar` | Both jars, in `target/` |
| `bb install` | Both jars into the local Maven repository |
| `bb npm` | Both npm packages, in `dist/` and `directives/npm/dist/` |
| `bb build` | All tests, then all builds |
| `bb check FILE.psosf` | Check an archive for errors and print a JSON report |
| `bb clean` | Remove build output |
| `bb conformance:generate` | Regenerate the conformance cases |

The version lives in `VERSION`; `bb jar` and `bb npm` refuse to build if `package.json` disagrees.
