# @pebblestream/psosf-directives

Check [PSOSF](https://docs.pebblestream.com/docs/pebble-stream-open-spreadsheet-format) spreadsheets against everything Pebble Stream can verify without compiling them: structure, every PebbleScript directive, and the worksheet rules in the [PebbleScript Reference](https://docs.pebblestream.com/docs/pebblescript-reference). Each error names the worksheet, the column and the directive, usually with a suggested fix, which makes the check a convenient feedback loop for scripts and AI agents.

```
npm install @pebblestream/psosf-directives
```

```javascript
import { readFileSync } from "node:fs";
import { checkZip, parseLine } from "@pebblestream/psosf-directives";

const report = checkZip(readFileSync("distributions.psosf"), { "allowed-functions": ["SUM", "IF"] });
if (!report.passed) {
  for (const e of report.errors) console.log(`${e.sheet} › ${e.column}: ${e.message} ${e.fix ?? ""}`);
}

parseLine('pebblestream:sort("Last", "Date":desc)');
// { directive: "sort", syntax: "paren", args: ["Last", { column: "Date", order: "desc" }], warnings: [] }
```

Works in Node and in the browser. Built from the same ClojureScript as the JVM library, so both give the same results.

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
