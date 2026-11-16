# @pebblestream/psosf

Read, write and classify [Pebble Stream Open Spreadsheet Format](https://docs.pebblestream.com/docs/pebble-stream-open-spreadsheet-format) (PSOSF) archives in JavaScript and TypeScript, in Node or the browser. PSOSF is a plain-text form of a spreadsheet: one CSV file per worksheet, plus a manifest, in a ZIP archive.

```
npm install @pebblestream/psosf
```

```javascript
import { readFileSync } from "node:fs";
import { classifyZip, unzip, read, write, zip } from "@pebblestream/psosf";

const result = classifyZip(readFileSync("account-balances.psosf"), { "allowed-functions": ["SUM", "IF"] });
// { level: "acceptable", errors: [], warnings: [], "acceptance-errors": [], archive: {...} }

const { archive } = read(unzip(readFileSync("account-balances.psosf")).entries);
archive.manifest.description = "Updated.";
const bytes = zip(write(archive));
```

`classifyZip` reports one of three levels: `invalid` (not a well-formed archive), `valid` (well-formed and convertible to Excel) or `acceptable` (also passes the Pebble Stream checks that need no compilation). Archives are read in memory as untrusted input, with limits on size and compression ratio.

To check a spreadsheet against the PebbleScript worksheet rules as well, use [`@pebblestream/psosf-directives`](https://www.npmjs.com/package/@pebblestream/psosf-directives).

TypeScript types are included. Built from the same ClojureScript as the [JVM library](https://github.com/GeorgetownSoftwareHouse/pebble-stream-psosf), so both give the same results.

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
