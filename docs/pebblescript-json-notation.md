---
title: PebbleScript JSON Notation
excerpt: A structured JSON form of PebbleScript for AI agents and tools, paired one-to-one with textual PebbleScript.
---

# PebbleScript JSON Notation

PebbleScript JSON Notation is a structured form of PebbleScript designed for AI agents and tools. Every directive in JSON Notation corresponds to exactly one directive in textual PebbleScript, and each can be converted to the other mechanically.

The two forms have different jobs:

| Form | Used for |
|---|---|
| **JSON Notation** | Inference and tool calls: an agent proposing, reviewing or editing directives, and APIs that accept directives as structured data. |
| **Textual PebbleScript** | Storage: Excel header notes and line 1 of [PSOSF](https://docs.pebblestream.com/docs/pebble-stream-open-spreadsheet-format) worksheet files. |

JSON Notation is never written to a PSOSF archive or an Excel workbook. A tool that receives JSON Notation converts it to textual PebbleScript before writing.

Why a second form? Textual PebbleScript passes arguments by position. In `pebblestream:join("Rates", "id", "rate-id", "date", "rate-date")`, nothing but position says which values pair up. JSON Notation names every argument and groups repeated ones, so the same directive can be validated exactly with JSON Schema and is harder for an agent to get wrong.

---

## PebbleScript is declarative

PebbleScript describes what a worksheet is, not a sequence of steps to perform. Where a directive is written, and in what order, has no effect on when it runs: the same directives produce the same result whether they appear in one annotation or spread across several columns, and in any order.

Transformative directives on a worksheet always run in this fixed order:

1. [from](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#from)
2. [cartesian-product](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#cartesian-product)
3. [join](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#join)
4. [outer-join](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#outer-join)
5. [filter](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#filter)
6. [sort](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#sort)
7. [group](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#group), together with the directives that run within it:
   [coalesce](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#coalesce),
   [sum](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#sum),
   [ratio](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#ratio),
   [mult](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#mult),
   [equal](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#equal) and
   [regex](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#regex). The order among these does not matter. They may read the same column, but no two may write to the same column.
8. [group-unique](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#group-unique) (deprecated)
9. [group-sum](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#group-sum) (deprecated)
10. [group-ratio](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#group-ratio) (deprecated)
11. [header-map](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#header-map)
12. [split](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#split) (deprecated)
13. [prune](https://github.com/GeorgetownSoftwareHouse/pebble-stream-documentation#prune), which always runs last

All transformative directives run before the worksheet's formulas. A worksheet is computed in three stages:

1. The transformative directives run, in the order above.
2. Formula columns are filled down to the transformed rows and calculated.
3. Annotative directives that check cell values run on the completed worksheet.

For example, on a worksheet with `join`, `filter` and `group`, rows are joined first, then filtered, then grouped, however the three are written. So a filter can test a column brought in by the join, but it always sees rows before they are grouped and cannot test a group's total. To remove groups by their total, use `prune`, which runs after grouping: `pebblestream:prune("total", ">=0")` keeps only groups with a non-negative total. Neither `filter` nor `prune` can test a formula column, since both run before formulas are calculated; to remove rows by a formula's result, compute it on one worksheet and filter on a second worksheet that reads from it.

After the transformative directives run, every formula column is filled down to the transformed rows automatically. This is implied by the design; don't write `match-rows` to get it. An explicit `match-rows` cannot be combined with a transformative directive on the same worksheet.

Annotative directives that check cell values, such as `fail`, `warn`, `not-blank`, `unique` and `allowed-values`, run on the completed worksheet, after its transformative directives.

`fail` and `warn` are evaluated on formula cells. They annotate a formula column and test that column's formulas after the formulas have been filled down to the transformed rows. They never check a cell produced directly by a transformative directive. To check a value a transformative directive produces, such as a group's total, add a formula column that tests it and put `fail` or `warn` on that column:

| | A | B | C |
|---|---|---|---|
| **Note** | `pebblestream:from("Accounts")`<br>`pebblestream:group("type")`<br>`pebblestream:sum("amount", "total")` | | `pebblestream:fail` |
| **1** | **type** | **total** | **total is negative** |
| **2** | | | `=B2<0` |

`type` and `total` are produced by the group step; `total is negative` is a formula, filled down to one row per group, and `fail` stops the run for any group whose total is negative.

Annotative directives that do not check cell values, such as `hint`, `out` and the template directives, have no order. A worksheet may have `fail` and `warn` on as many columns as it needs.

When writing JSON Notation, order directives in the sequence above for readability. The order of items in an annotation array preserves where comments sit; it never changes what the worksheet computes.

The position of the remaining transformative directives in this order will be documented as it is confirmed.

### Declared columns determine the result

A worksheet's headers determine which columns appear after its transformation. A column from the source worksheet appears in the result only if the worksheet declares it as a header; any other source column is left out. The one requirement is that every column a transformative directive writes to must be declared.

In the `fail` example above, `total` must be declared because `sum` writes to it. `type`, the grouping column, is not a target, so it may be left out; include it when consumers of the output need to know which group each row belongs to. The same holds for `header-map`: each new header it writes must be declared, while source columns it does not touch are optional.

For example, take two worksheets with two columns each:

| fund | manager |
|---|---|
| Fund A | Lee |
| Fund B | Okafor |

| period | end date |
|---|---|
| Q1 | 2026-03-31 |
| Q2 | 2026-06-30 |

A worksheet that takes their cartesian product can declare just one of the four columns:

| | A |
|---|---|
| **Note** | `pebblestream:from("Funds")`<br>`pebblestream:cartesian-product("Periods")` |
| **1** | **fund** |

Its result is the single `fund` column. The rows are still the full product, one per fund and period, so `Fund A` and `Fund B` each appear twice. Leaving columns out changes which columns appear, not how many rows there are.

---

## Directives

A directive is a JSON object. The `directive` key names it, without the `pebblestream:` prefix. Every other key is an argument.

```
pebblestream:join("Rates", "id", "rate-id", "date", "rate-date")
```

```json
{
  "directive": "join",
  "sheet": "Rates",
  "on": [
    {"column": "id", "source-column": "rate-id"},
    {"column": "date", "source-column": "rate-date"}
  ]
}
```

Rules:

- Keys are lowercase and hyphenated.
- Each directive accepts only the keys listed for it in the [catalog](#directive-catalog). Unknown keys are an error.
- An argument repeated in text (such as the sheets in `from`) is a JSON array. A repeated group of arguments (such as the column pairs in `join`) is an array of objects.
- An optional argument is omitted when absent. It is never `null`.
- Header names, sheet names and other text are JSON strings. Numbers are JSON numbers.
- Any argument may instead be a [cell reference](#cell-references).

## Cell references

Any argument can be a cell reference instead of a literal value. In JSON Notation a reference is an object with a single `ref` key holding the reference in Excel's A1 notation:

```
pebblestream:filter("amount", ">0", "fund", Inputs!B2)
pebblestream:generate-rows('Run Settings'!$B$1)
```

```json
{"directive": "filter",
 "conditions": [{"column": "amount", "predicate": ">0"},
                {"column": "fund", "predicate": {"ref": "Inputs!B2"}}]}

{"directive": "generate-rows", "count": {"ref": "'Run Settings'!$B$1"}}
```

The object form keeps a reference distinct from a string that happens to look like one: `{"ref": "Inputs!B2"}` is a reference, while `"Inputs!B2"` is the literal text `Inputs!B2`.

In textual PebbleScript, the worksheet name is what tells a reference from a name. A header such as `Q1` or `ID5` is also a valid cell address, so an unqualified address is always read as a name: `pebblestream:group(ID5)` groups by the header `ID5`, and `pebblestream:group(Setup!ID5)` groups by the header held in cell `ID5` of `Setup`.

Rules:

- `ref` holds one cell, qualified by the name of its worksheet and optionally absolute (`Inputs!B2`, `Inputs!$B$2`, `'Run Settings'!$B$1`). The worksheet name is required. A sheet name containing spaces or punctuation is enclosed in single quotes, with any single quote inside it doubled, as in Excel.
- A cell address without a worksheet name, such as `B2`, is not a reference.
- A reference can stand anywhere a literal can, including inside arrays (`{"directive": "from", "sheets": [{"ref": "Setup!A2"}, "Accounts"]}`) and inside repeated groups.
- In the catalog's key tables, each key's type describes the literal it accepts. Every key also accepts a reference.

## Annotations

An annotation is the full content of one column's header note: directives and free-form comments, in order. In JSON Notation it is an array whose items are either a directive object or a comment object.

```
Totals of all ledger balances, by account type.
pebblestream:from("Accounts")
pebblestream:group("type")
pebblestream:sum("amount")
Rebuilt nightly from the general ledger extract.
```

```json
[
  {"comment": "Totals of all ledger balances, by account type."},
  {"directive": "from", "sheets": ["Accounts"]},
  {"directive": "group", "columns": ["type"]},
  {"directive": "sum", "column": "amount"},
  {"comment": "Rebuilt nightly from the general ledger extract."}
]
```

A comment object holds exactly one line of text. Its text must not contain a line break, and must not begin (after leading whitespace) with `pebblestream:`, since that line would be read back as a directive.

An empty annotation is an empty array.

---

## Converting between JSON and text

### JSON to text

Each item becomes one line, in order. A comment item becomes its text. A directive item becomes:

1. `pebblestream:` followed by the directive name.
2. If the directive has no arguments present, nothing more. This is the bare form, e.g. `pebblestream:unique`.
3. Otherwise, the arguments in the directive's positional order, separated by `, ` and enclosed in parentheses. Repeated groups are flattened in array order, and an absent optional argument is left out.
4. Strings are enclosed in double quotes; numbers are written without quotes; cell references are written without quotes, exactly as held in `ref`. A column predicate is written as its quoted operator, `&`, and the quoted header (`"<="&"Balance"`). A sort order on a `sort` column is written as its `:asc` or `:desc` suffix.

The colon form (`pebblestream:name:a,b`) is deprecated and is never produced.

### Text to JSON

A converter accepts every form textual PebbleScript allows: bare, parentheses, and the deprecated colon form. Which form was used is not recorded in JSON Notation.

A quoted argument becomes a string. A worksheet or column name that contains no space may also be written without quotes (`pebblestream:from(Accounts)`), and becomes the same string. An unquoted number becomes a number, an unquoted reference qualified by its worksheet name (`Inputs!B2`) becomes a `{"ref": ...}` object, and an operator joined to a header with `&` becomes a column predicate object. An unquoted cell address without a worksheet name, such as `Q1`, is a name and becomes a string.

### Quotes and backslashes in text

Inside a quoted string argument:

- A quote is written as two quotes, as in an Excel formula: `"12"" pipe"` is `12" pipe`. A backslash before the quote also works: `"12\" pipe"`.
- A backslash escapes the character after it, so a literal backslash is written as two: `"C:\\data"` is `C:\data`.
- A quote that is neither doubled nor escaped makes the directive invalid.

A backslash before any other character is removed, so `"C:\data"` reads as `C:data`. Because that is rarely what was meant, the compiler warns whenever it happens.

When converting JSON to text, a string is written with its quotes doubled and its backslashes doubled, so it always reads back exactly and never draws the warning. A name written without quotes must contain no space; quote any name that contains punctuation.

### Round trips

- **JSON → text → JSON** returns the same JSON, apart from key order.
- **Text → JSON → text** returns the same directives, in canonical form: a directive written with the colon form comes back with parentheses, and `pebblestream:unique()` comes back as `pebblestream:unique`.

Because the second round trip normalizes the text, converters that move annotations between PSOSF and Excel copy the text verbatim and never pass it through JSON Notation. Normalization happens only when an agent's JSON is written as new text.

---

## Directive catalog

Each entry shows the textual form and its JSON Notation, followed by the directive's keys in positional order.

Argument names marked *provisional* are placeholders. The directive's arguments are positional in text, and their meanings have not yet been named; the names will change before this notation is final.

### Sources

#### from

```
pebblestream:from("Accounts", "Adjustments")
```
```json
{"directive": "from", "sheets": ["Accounts", "Adjustments"]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `sheets` | array of sheet names, at least one, distinct | Yes | All arguments |

#### cartesian-product

```
pebblestream:cartesian-product("Funds", "Periods")
```
```json
{"directive": "cartesian-product", "sheets": ["Funds", "Periods"]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `sheets` | array of sheet names, at least one, distinct | Yes | All arguments |

#### join and outer-join

`join` combines each row of the `from` worksheet with the matching rows of another worksheet (an inner join); `outer-join` also keeps rows that have no match. Rows match on the column pairs given in `on`. When `on` is omitted, they match on every header the two worksheets have in common. Each directive can be used once per worksheet.

```
pebblestream:join("Orders")
pebblestream:join("Taxable", "From", "Account From", "To", "Account To")
```
```json
{"directive": "join", "sheet": "Orders"}

{"directive": "join", "sheet": "Taxable",
 "on": [{"column": "From", "source-column": "Account From"},
        {"column": "To", "source-column": "Account To"}]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `sheet` | sheet name | Yes | 1 |
| `on` | array of pairs | No; when omitted, rows match on the headers both worksheets share | 2 onward, two per pair |
| `on[].column` | header on the `from` worksheet | Yes | First of each pair |
| `on[].source-column` | header on `sheet` | Yes | Second of each pair |

`outer-join` has the same keys.

### Selecting, ordering and renaming rows

#### filter and prune

`filter` keeps only the rows that meet every one of its conditions. To keep rows that meet any of several sets of conditions, write one `filter` per set: separate `filter` directives on a worksheet are combined with OR.

A condition tests one column against a predicate:

- A literal predicate compares the column with a value: `">0"`, `"<60"`, or `"blue"` for equality.
- A column predicate compares the column with another column in the same row. In text it joins an operator to a quoted header with `&`, as in `"<="&"Balance"`; in JSON it is an object with `operator` and `column`.
- A condition with no predicate keeps rows where the column's value is `TRUE`. It must be the directive's only condition.

```
pebblestream:filter("Active")
pebblestream:filter("Amount", ">0", "Color", "blue", "Amount", "<="&"Balance")
```
```json
{"directive": "filter", "conditions": [{"column": "Active"}]}

{"directive": "filter",
 "conditions": [{"column": "Amount", "predicate": ">0"},
                {"column": "Color", "predicate": "blue"},
                {"column": "Amount", "predicate": {"operator": "<=", "column": "Balance"}}]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `conditions` | array, at least one | Yes | All arguments, two per condition |
| `conditions[].column` | header | Yes | First of each pair |
| `conditions[].predicate` | literal predicate (text), or column predicate (object) | No, only when it is the sole condition | Second of each pair |
| `conditions[].predicate.operator` | `=`, `<>`, `<`, `<=`, `>` or `>=` | Yes, in a column predicate | Before the `&` |
| `conditions[].predicate.column` | header on the same worksheet | Yes, in a column predicate | After the `&` |

`prune` has the same keys and works the same way as `filter`, but always runs last, after every other transformative directive and before formulas are calculated. Use `filter` to remove rows before they are joined or grouped, and `prune` to remove rows from the transformed result, for example groups whose total fails a test.

#### sort

Columns are sorted in the order given: by the first column, then by the second within it, and so on. Each column sorts ascending unless it carries the `:desc` suffix; `:asc` may be written explicitly.

```
pebblestream:sort("Last", "First", "Date":desc)
```
```json
{"directive": "sort",
 "columns": [{"column": "Last"},
             {"column": "First"},
             {"column": "Date", "order": "desc"}]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `columns` | array of sort columns, at least one, distinct headers | Yes | All arguments, in sort priority order |
| `columns[].column` | header | Yes | The quoted header |
| `columns[].order` | `"asc"` or `"desc"` | No; ascending when omitted | The `:asc` or `:desc` suffix |

An `order` key is written as a suffix only when present, so `{"column": "Date", "order": "asc"}` becomes `"Date":asc` and `{"column": "Date"}` becomes `"Date"`. Both sort ascending, and each round-trips to itself.

#### header-map

```
pebblestream:header-map("Acct No", "account", "Bal", "amount")
```
```json
{"directive": "header-map",
 "mappings": [{"column": "Acct No", "new-column": "account"},
              {"column": "Bal", "new-column": "amount"}]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `mappings` | array, at least one | Yes | All arguments, two per mapping |
| `mappings[].column` | existing header | Yes | First of each pair |
| `mappings[].new-column` | new header | Yes | Second of each pair |

### Grouping

#### group

```
pebblestream:group("fund", "type")
```
```json
{"directive": "group", "columns": ["fund", "type"]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `columns` | array of headers, at least one, distinct | Yes | All arguments |

#### group-unique, group-sum and group-ratio (deprecated)

These directives are deprecated. Don't use them in new worksheets. Existing worksheets that contain them still convert, with the same keys as `group`:

```
pebblestream:group-sum("fund")
```
```json
{"directive": "group-sum", "columns": ["fund"]}
```

#### sum, min, max, average, mult, equal and ratio

```
pebblestream:sum("amount")
pebblestream:sum("amount", "total")
```
```json
{"directive": "sum", "column": "amount"}
{"directive": "sum", "column": "amount", "new-column": "total"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `column` | header | Yes | 1 |
| `new-column` | header | No | 2 |

`min`, `max`, `average`, `mult`, `equal` and `ratio` have the same keys.

Within the group step, several directives may read the same column, but no two may write to the same column. A directive writes to `new-column` when it is given, and otherwise to `column`. So `sum` and `ratio` can both read `amount` if they write to different new columns:

```json
[
  {"directive": "sum", "column": "amount", "new-column": "total"},
  {"directive": "ratio", "column": "amount", "new-column": "share"}
]
```

But two directives writing to `total`, or two directives on `amount` that both omit `new-column`, are an error.

#### balance

```
pebblestream:balance("amount", "debit", "credit")
```
```json
{"directive": "balance", "column": "amount", "new-column-1": "debit", "new-column-2": "credit"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `column` | header | Yes | 1 |
| `new-column-1` | header (*provisional*) | Yes | 2 |
| `new-column-2` | header (*provisional*) | Yes | 3 |

#### coalesce and expand

```
pebblestream:coalesce
```
```json
{"directive": "coalesce"}
```

No keys. `expand` is the same.

#### yield and npv

```
pebblestream:yield("cash-flow", "date", "price", "settlement", "yield")
```
```json
{"directive": "yield",
 "column-1": "cash-flow", "column-2": "date",
 "column-3": "price", "column-4": "settlement",
 "new-column": "yield"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `column-1` | header (*provisional*) | Yes | 1 |
| `column-2` | header (*provisional*) | Yes | 2 |
| `column-3` | header (*provisional*) | Yes | 3 |
| `column-4` | header (*provisional*) | Yes | 4 |
| `new-column` | header | Yes | 5 |

`npv` has the same keys.

#### pivot

```
pebblestream:pivot("account", "amount", "period", "text", "Q1")
```
```json
{"directive": "pivot",
 "column": "account", "value": "amount", "pivot-column": "period",
 "filter-type": "text", "filter-value": "Q1"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `column` | header (*provisional*) | Yes | 1 |
| `value` | cell value (*provisional*) | Yes | 2 |
| `pivot-column` | header (*provisional*) | Yes | 3 |
| `filter-type` | data type (*provisional*) | Yes | 4 |
| `filter-value` | value (*provisional*) | No | 5 |

### Allocation and paths

#### apportion and relieve

```
pebblestream:apportion("partner", "amount", "ratio", "period")
```
```json
{"directive": "apportion",
 "column-1": "partner", "column-2": "amount", "column-3": "ratio", "column-4": "period"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `column-1` … `column-4` | headers, distinct (*provisional*) | Yes | 1–4 |

`relieve` has the same keys.

#### paths

```
pebblestream:paths("parent", "child", "weight")
```
```json
{"directive": "paths", "column-1": "parent", "column-2": "child", "column-3": "weight"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `column-1` … `column-3` | headers, distinct (*provisional*) | Yes | 1–3 |

#### paths-operation

```
pebblestream:paths-operation("product", "effective-ownership")
```
```json
{"directive": "paths-operation", "operation": "product", "new-column": "effective-ownership"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `operation` | paths operation | Yes | 1 |
| `new-column` | header | Yes | 2 |

### Generating rows

#### match-rows

```
pebblestream:match-rows("Accounts")
```
```json
{"directive": "match-rows", "sheet": "Accounts"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `sheet` | sheet name | Yes | 1 |

#### generate-rows

```
pebblestream:generate-rows(12)
```
```json
{"directive": "generate-rows", "count": 12}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `count` | number | Yes | 1 |

#### stop

`stop` annotates a column, and may appear on more than one column of a worksheet. The first column to evaluate to true, in execution order, stops the worksheet's execution. Execution order follows the dependencies between formulas, not the columns' positions, so a stop column to the left of another is not necessarily evaluated first.

The optional number is the most rows the worksheet will generate. When it is omitted, the limit configured for the runtime applies. Always set it, so the worksheet's behavior doesn't depend on how the runtime is configured.

```
pebblestream:stop(1000)
pebblestream:stop
```
```json
{"directive": "stop", "max-rows": 1000}
{"directive": "stop"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `max-rows` | number | No; the runtime's limit applies when omitted | 1 |

### Annotating columns and worksheets

#### Directives without arguments

These directives take no arguments. In JSON Notation they have only the `directive` key, and in text they are written bare.

```
pebblestream:not-blank
```
```json
{"directive": "not-blank"}
```

`unique`, `grouped`, `warn`, `fail`, `not-blank`, `ignore`, `do-not-compile`, `out`

`fail` and `warn` may each appear on more than one column of a worksheet.

#### sorted

`sorted` stops execution if the column it annotates is not in order. It checks ascending order unless `order` is `desc`. When several columns on a worksheet carry `sorted`, they form one combined sort key, and `priority` gives each column's place in it for breaking ties (lower first).

```
pebblestream:sorted
pebblestream:sorted("desc", 2)
```
```json
{"directive": "sorted"}
{"directive": "sorted", "order": "desc", "priority": 2}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `order` | `asc` or `desc` | No; ascending when omitted | 1 |
| `priority` | number | No; needed when several columns carry `sorted` | 2, or 1 when `order` is omitted |

#### allowed-values

```
pebblestream:allowed-values("individual", "corporate")
```
```json
{"directive": "allowed-values", "values": ["individual", "corporate"]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `values` | array, at least one | Yes | All arguments |

#### hint

`hint` attaches a named annotation to a worksheet for agents and other tools to read. Each hint has a key and an optional value; hints with the same key on one worksheet are collected into a list of values for that key.

```
pebblestream:hint("balance", "Use the closing balance, not the average balance.")
pebblestream:hint("reviewed")
```
```json
{"directive": "hint", "key": "balance", "value": "Use the closing balance, not the average balance."}
{"directive": "hint", "key": "reviewed"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `key` | text | Yes | 1 |
| `value` | text | No; recorded as empty when omitted | 2 |

#### identical-columns

```
pebblestream:identical-columns("Budget", "Actuals")
```
```json
{"directive": "identical-columns", "sheets": ["Budget", "Actuals"]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `sheets` | array of sheet names, at least one | Yes | All arguments |

#### id

`id` marks a column as part of a worksheet's primary key. Every column marked with the same `id` forms one key, and the combination of their values must be unique across the worksheet. This is a stronger check than `unique`, which checks a single column on its own. A worksheet can have more than one key by giving each a group name.

```
pebblestream:id
pebblestream:id("account key")
```
```json
{"directive": "id"}
{"directive": "id", "group": "account key"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `group` | text | No; columns without a group form the worksheet's unnamed key | 1 |

#### Column types: text, number, boolean and date

These directives set the type of the values in the column they annotate, overriding the type Excel would otherwise give them. Where possible, rely on the column's declared type instead.

```
pebblestream:date
```
```json
{"directive": "date"}
```

No keys. `text`, `number` and `boolean` are the same. A column annotated with `date` accepts text dates in the forms `yyyy-MM-dd`, `yyyy/MM/dd`, `MM/dd/yyyy` and `MM-dd-yyyy`, with or without leading zeros on the month and day, and converts them to Excel dates.

### Workflows

Workflow directives tell Pebble Stream Flow how to execute a spreadsheet within a workflow. They take effect when the spreadsheet runs in Flow and do not change what a single execution computes.

#### workflow-each, workflow-group and workflow-all

These directives annotate the driving input worksheet, and decide how its rows are divided among executions.

| Directive | Placed on | Effect |
|---|---|---|
| `workflow-each` | The driving input worksheet | One execution per row, run in parallel. |
| `workflow-group` | Each column holding part of the group key | One execution per group of rows sharing the same key values, run in parallel. |
| `workflow-all` | The driving input worksheet | One execution for all rows. This is the default, so it rarely needs to be written. |

Only one of the three can be used in a spreadsheet.

```
pebblestream:workflow-each
pebblestream:workflow-group
pebblestream:workflow-all
```
```json
{"directive": "workflow-each"}
{"directive": "workflow-group"}
{"directive": "workflow-all"}
```

No keys.

#### Iteration: workflow-iterate, workflow-terminate and workflow-filter

An iterative workflow runs repeatedly until it is told to stop. A spreadsheet containing `workflow-iterate` or `workflow-terminate` is the workflow's iteration spreadsheet. A workflow has at most one, and Flow runs it before every execution of the workflow, including the first. Iteration spreadsheets are meant to be small and quick; they decide whether and how the workflow runs again, not the workflow's main processing.

`workflow-iterate` and `workflow-terminate` cannot be used in the same spreadsheet.

##### workflow-iterate

Runs the workflow a fixed number of times.

```
pebblestream:workflow-iterate(10)
```
```json
{"directive": "workflow-iterate", "iterations": 10}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `iterations` | number | Yes | 1 |

##### workflow-terminate

Annotates a column of the iteration spreadsheet. The workflow stops when any cell in that column evaluates to `TRUE`, so when to stop is computed by the column's formulas. The number is a safety limit: the workflow never runs more than this many times, even if no cell becomes `TRUE`.

```
pebblestream:workflow-terminate(10)
```
```json
{"directive": "workflow-terminate", "max-iterations": 10}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `max-iterations` | number | Yes | 1 |

##### workflow-filter

Annotates a worksheet of the iteration spreadsheet. Its headers name key columns, and its rows give the key values to process in the next iteration. For every input worksheet in the workflow that has all of those columns, only rows matching one of the filter rows are sent to the workflow; several filter rows are combined with OR. Without `workflow-filter`, each iteration receives the whole input again.

```
pebblestream:workflow-filter
```
```json
{"directive": "workflow-filter"}
```

No keys. `workflow-filter` can be used with either `workflow-iterate` or `workflow-terminate`.

### Templates

#### template-name

```
pebblestream:template-name("Capital Account Statement")
```
```json
{"directive": "template-name", "name": "Capital Account Statement"}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `name` | template name | Yes | 1 |

#### template-out-style

```
pebblestream:template-out-style("number.percentage", "number.currency")
```
```json
{"directive": "template-out-style", "styles": ["number.percentage", "number.currency"]}
```

| Key | Type | Required | Text position |
|---|---|---|---|
| `styles` | array | No | All arguments |

#### Template directives without arguments

`block-id`, `template-row`, `template-column`, `leftmost-column`, `rightmost-column`, `top-row`, `bottom-row`, `block-row-count`, `row-oriented-headers`, `column-oriented-headers`, `template-out-header`, `template-out-cell`, `template-out-down`, `template-out-right`, `template-out-group`, `template-out-value-type`, `template-out-generate-clean`

```
pebblestream:template-out-down
```
```json
{"directive": "template-out-down"}
```

---

## Validation

Because every argument is named and every repeated group is an array of objects, each directive's JSON Notation can be described exactly in JSON Schema. For `join`:

```json
{
  "type": "object",
  "properties": {
    "directive": {"const": "join"},
    "sheet": {"anyOf": [{"$ref": "#/$defs/sheet"}, {"$ref": "#/$defs/cell-ref"}]},
    "on": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "properties": {
          "column": {"anyOf": [{"$ref": "#/$defs/header"}, {"$ref": "#/$defs/cell-ref"}]},
          "source-column": {"anyOf": [{"$ref": "#/$defs/header"}, {"$ref": "#/$defs/cell-ref"}]}
        },
        "required": ["column", "source-column"],
        "additionalProperties": false
      }
    }
  },
  "required": ["directive", "sheet"],
  "additionalProperties": false
}
```

Here `#/$defs/cell-ref` is an object with one required key, `ref`, whose value is a worksheet-qualified cell reference as described under [Cell references](#cell-references), and no other keys.

JSON Schema checks the shape of a directive. It cannot check rules that depend on the spreadsheet, such as whether `source-column` exists on the sheet named in `sheet`, or whether a directive's requires and excludes are satisfied by the other directives on the worksheet. The compiler checks those; submit the worksheet to the compiler service's validate endpoint before deploying.

---

## Directives not yet in this notation

The Board directives and the connector directives will be added once their signatures are confirmed. `div/0`, `header-count` and `split` are deprecated and have no JSON Notation. `regex` is disabled.
