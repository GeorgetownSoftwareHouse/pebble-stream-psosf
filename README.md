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
| PebbleScript text ⇄ JSON Notation | Done (in `psosf-directives`) |
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

## Directive library

`com.pebblestream/psosf-directives` is a separate artifact holding the PebbleScript directive registry (a [Malli](https://github.com/metosin/malli) schema of every directive's signature, exclusions, execution order and status), the directive line parser, and the converter between textual PebbleScript and [PebbleScript JSON Notation](https://docs.pebblestream.com/docs/pebblescript-json-notation). The core library does not depend on it.

```clojure
(require '[pebblestream.directives.notation :as n])

(n/text->json "pebblestream:join(\"Rates\", \"id\", \"rate-id\")")
;; => {:value {"directive" "join", "sheet" "Rates",
;;             "on" [{"column" "id", "source-column" "rate-id"}]}
;;     :errors [] :warnings []}

(n/json->text {"directive" "group" "columns" ["fund"]})
;; => {:value "pebblestream:group(\"fund\")" :errors [] :warnings []}

;; A whole header note: directive and comment lines, in order
(n/annotation->json "Totals by type.\npebblestream:group(\"type\")")
(n/json->annotation [{"comment" "Totals by type."} {"directive" "group" "columns" ["type"]}])
```

Values are JSON-shaped (string keys), as any JSON reader produces. Both directions check the directive against the registry, so a value is returned only for a directive whose arguments fit its signature; otherwise `:errors` names each problem. Warnings report deprecated directives, the deprecated colon form, and backslashes that escape nothing. `json->text` always writes canonical text, which `text->json` reads back exactly; `pebblestream.directives.text` exposes the line parser and renderer on their own.

## Repository layout

```
pebble-stream-psosf/
├── src/psosf/                 the psosf library (.cljc, plus js.cljs for npm)
├── src/java/                  the Java entry point, compiled into the jar
├── test/                      JVM and ClojureScript tests, and the test runners
├── directives/
│   ├── src/pebblestream/      the directive registry, line parser and JSON Notation
│   └── test/                  their tests
├── conformance/               language-neutral cases every binding must pass
├── schema/                    the JSON Schema of the API contract, version 1
├── docs/                      the PSOSF and PebbleScript JSON Notation specifications
├── build.clj                  jar builds (tools.build)
├── shadow-cljs.edn            the npm build
├── package.json, index.d.ts   the npm package and its TypeScript types
├── bb.edn                     development tasks
└── VERSION                    the release version, shared by every artifact
```

## Building and testing

Requires a JDK (11 or later), the [Clojure CLI](https://clojure.org/guides/install_clojure), [babashka](https://babashka.org/) and Node.js.

| Command | Does |
|---|---|
| `bb test` | Every test suite: JVM, directives and ClojureScript |
| `bb jar` | Both jars, in `target/` |
| `bb install` | Both jars into the local Maven repository |
| `bb npm` | The npm package, in `dist/` |
| `bb build` | All tests, then all builds |
| `bb clean` | Remove build output |
| `bb conformance:generate` | Regenerate the conformance cases |

The version lives in `VERSION`; `bb jar` and `bb npm` refuse to build if `package.json` disagrees.
