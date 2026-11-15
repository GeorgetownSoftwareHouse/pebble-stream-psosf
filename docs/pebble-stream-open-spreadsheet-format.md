---
title: Pebble Stream Open Spreadsheet Format (PSOSF)
excerpt: A plain-text format for Pebble Stream spreadsheets that people can read, tools can diff, and LLMs can generate reliably.
---

# Pebble Stream Open Spreadsheet Format (PSOSF)

The Pebble Stream Open Spreadsheet Format (PSOSF) is a plain-text representation of a Pebble Stream computable spreadsheet. It carries everything the Pebble Stream compiler reads from an Excel workbook (PebbleScript, column types, headers, values and formulas) in a set of CSV files bundled in a ZIP archive.

PSOSF is designed to be:

- **Readable.** Each worksheet is a CSV file you can open in any editor.
- **Diffable.** Changes to logic show up as line changes in version control.
- **Generatable.** LLMs and other tools can produce PSOSF far more reliably than Excel's XML-based format.
- **Convertible.** A PSOSF archive converts to an Excel workbook and back without losing anything the compiler uses.

> **For AI agents.** Prefer PSOSF over `.xlsx` when creating or editing Pebble Stream spreadsheets. Read the [Notes for agents](#notes-for-agents) section before generating an archive.

This page describes format version **1.1**. Archives written to version 1.0 (no manifest) remain readable; see [Versioning](#versioning).

---

## Archive structure

A PSOSF spreadsheet is a ZIP archive. Its media type is `application/vnd.pebblestream.psosf+zip`, and its file extension is `.psosf`. Readers also accept `.zip`, and identify an archive by its content and media type rather than by extension alone.

| | |
|---|---|
| Media type | `application/vnd.pebblestream.psosf+zip` |
| File extension | `.psosf` |
| Magic number | `50 4B 03 04` (ZIP local file header) |

```
my-model.psosf
├── manifest.json          (required in 1.1)
├── signatures.json        (optional)
├── Accounts.csv           (one file per worksheet)
├── Account Totals.csv
└── tests/                 (optional)
    └── zero-balance/
        ├── test.json
        ├── inputs/
        │   └── Accounts.csv
        └── expected/
            └── Account Totals.csv
```

Rules:

- Worksheet files live in the root of the archive. Each is named `<worksheet name>.csv`, and the worksheet name is the file name without the `.csv` extension.
- Worksheet names follow Excel's rules: at most 31 characters, not blank, and none of the characters `\ / ? * [ ] :`. Names are unique without regard to case.
- File names in the archive are UTF-8 encoded.
- `manifest.json`, `signatures.json` and the `tests/` folder are reserved. No other files or folders may appear in the archive.

---

## The manifest

`manifest.json` describes the spreadsheet as a whole.

```json
{
  "description": "Validates ledger accounts and totals balances by account type.",
  "export": {
    "input-row-limit": 100,
    "mode": "compact"
  },
  "format": "psosf",
  "format-version": "1.1",
  "license": {
    "name": "MIT License",
    "spdx": "MIT",
    "url": "https://opensource.org/license/mit"
  },
  "name": "Account Balances",
  "namespace": "com.example.ledger",
  "sheets": [
    "Accounts",
    "Account Totals"
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `format` | Yes | Always `psosf`. |
| `format-version` | Yes | The PSOSF version the archive is written to, e.g. `1.1`. |
| `name` | Yes | The spreadsheet's name. |
| `namespace` | No | A namespace that qualifies the spreadsheet's worksheet names, so worksheets of the same name in different spreadsheets can be told apart. See [Namespaces](#namespaces). |
| `description` | No | A short description of what the spreadsheet does. |
| `export` | No | How the archive was exported from a workbook: `mode` is `compact` or `full`, and `input-row-limit` is the row limit a compact export applied to input worksheets. See [Compact and full export](#compact-and-full-export). Absent means `full`. |
| `license` | No | The license that covers the spreadsheet's logic. See [License](#license). |
| `sheets` | Yes | Every worksheet name, in workbook order. Each must have a matching `.csv` file, and every `.csv` file must be listed. |

Worksheet order in `sheets` controls the order of tabs when the archive is converted to Excel. It has no effect on computation; the compiler always computes worksheets in dependency order.

The manifest records what the spreadsheet is, not who wrote it or when. Authorship and approval are recorded in [signatures](#signatures), which change whenever someone signs, without changing the spreadsheet's content.

### License

`license` declares the terms under which the spreadsheet's logic (its formulas, PebbleScript, headers, types and styles) may be used, copied and changed.

| Field | Required | Meaning |
|---|---|---|
| `name` | Yes | The license's name, as a person would read it, e.g. `MIT License` or `Proprietary`. |
| `url` | Yes | Where the full license text can be read. |
| `spdx` | No | The license's [SPDX identifier](https://spdx.org/licenses/), e.g. `MIT` or `Apache-2.0`, so tools can recognize it without reading the text. For a license with no SPDX identifier, use a `LicenseRef-` identifier such as `LicenseRef-Acme-Commercial`. |

For example, a closed license:

```json
"license": {
  "name": "Acme Fund Services Commercial License",
  "spdx": "LicenseRef-Acme-Commercial",
  "url": "https://acme.example/legal/workflow-license"
}
```

The license covers the logic, not the data. Rows of input data that an archive carries (in a full export, or in tests) remain subject to whatever terms apply to that data.

An archive without `license` declares nothing; its absence does not grant any rights. Because the manifest is covered by the archive's [signatures](#signatures), a signed archive's license cannot be changed without invalidating them.

### JSON conventions

`manifest.json`, `signatures.json` and every `test.json` are written in a canonical form, so the same content always produces the same bytes:

- UTF-8, without a byte-order mark, with LF line endings and a final line feed.
- Two-space indentation, one key or array element per line.
- Object keys in sorted order at every level.
- Non-ASCII characters written as UTF-8 rather than escaped.

### Namespaces

Two spreadsheets can each have a worksheet called `Partners` that means different things. A namespace distinguishes them. When the manifest has a `namespace`, each worksheet also has a qualified name: the namespace, a forward slash, and the worksheet name.

| Namespace | Worksheet | Qualified name |
|---|---|---|
| `com.example.ledger` | `Accounts` | `com.example.ledger/Accounts` |
| `com.example.tax.partnership` | `Partners` | `com.example.tax.partnership/Partners` |

Rules:

- A namespace is one or more segments separated by dots. Each segment starts with a lowercase letter and contains only lowercase letters, digits and hyphens.
- Use a reverse domain name you control as the leading segments (for example `com.example`), followed by segments that place the spreadsheet within your own organization. This keeps namespaces from different publishers from colliding.
- The forward slash cannot appear in a worksheet name, so a qualified name always splits unambiguously at its last `/`.
- The namespace does not change anything inside the spreadsheet. Worksheet files, formulas and directives continue to use plain worksheet names.

Qualified names are for tools that work with worksheets across spreadsheets, such as catalogs, marketplaces and workflow tooling. The namespace also gives a spreadsheet a stable identity to which taxonomic and ontological descriptions can be attached.

---

## Worksheet files

Each worksheet file is a CSV file with a four-line preamble followed by the worksheet's cells.

| Line | Contents | Required |
|---|---|---|
| 1 | **Annotations**: the note attached to each header cell, holding PebbleScript and free-form comments | The line is required; each cell may be blank |
| 2 | **Types**: each column's primitive type: `text`, `number`, `boolean` or `date` | Yes, every cell |
| 3 | **Formatting**: an optional Zen palette style for each column | The line is required; each cell may be blank |
| 4 | **Headers**: the column headers | Yes, every cell |
| 5 onward | **Cells**: the worksheet's values and formulas, one worksheet row per line | Optional |

Every line has exactly as many fields as there are headers, including lines whose fields are all blank.

### Line 1: Annotations

In Excel, PebbleScript is written in the note on a header cell, and the same note may also contain free-form comments written by the pebbler. Line 1 carries that note exactly as it appears in Excel: one CSV field per column, holding the full text of that column's header note.

Within an annotation, each line is either PebbleScript or comment:

- A line whose first non-whitespace characters are `pebblestream:` is **PebbleScript**.
- Every other line is a **free-form comment**.

Lines are separated by line feeds inside the CSV field, so any annotation with more than one line must be quoted. PebbleScript and comment lines may appear in any order, and their order is preserved. The comment lines together form the column comment, which is available to workflow callers through the Pebble Stream spreadsheet runtime alongside the header name and type.

By convention, worksheet-level directives, such as `pebblestream:from` or `pebblestream:do-not-compile`, are written in the annotation of the first column, as they are in Excel. Follow this convention so worksheets are easy to read and review. The compiler does not require it and accepts a worksheet-level directive in any column's annotation.

Because the annotation is kept verbatim, converting between PSOSF and Excel never splits or merges text: the note in Excel and the field in line 1 are the same string.

For example, this annotation mixes a comment with three directives:

```
Totals of all ledger balances, by account type.
pebblestream:from("Accounts")
pebblestream:group("type")
pebblestream:sum("amount")
Rebuilt nightly from the general ledger extract.
```

Written as the first field of line 1, with CSV quoting applied (quotes inside the field are doubled):

```
"Totals of all ledger balances, by account type.
pebblestream:from(""Accounts"")
pebblestream:group(""type"")
pebblestream:sum(""amount"")
Rebuilt nightly from the general ledger extract.",
```

Quotes are escaped once for PebbleScript and again for CSV. A filter on the header `Product` for the value `12" pipe` is written in PebbleScript with the inner quote doubled:

```
pebblestream:filter("Product", "12"" pipe")
```

and in line 1, where CSV doubles every quote again:

```
"pebblestream:filter(""Product"", ""12"""" pipe"")"
```

(In PebbleScript JSON Notation the same value is the JSON string `"12\" pipe"`.) Converters apply both layers automatically; only a file written by hand needs this care.

### Line 2: Types

Each field is one of `text`, `number`, `boolean` or `date`, in lowercase. Every column must have a type.

A column's annotation may also contain a type directive (`pebblestream:text`, `pebblestream:number`, `pebblestream:boolean` or `pebblestream:date`). The type directive is interpreted by the runtime and overrides the type in line 2. A type directive that differs from line 2 is not an error, and the spreadsheet compiles.

### Line 3: Formatting

Each field may hold a style for that column. A blank field means the column has no style.

A style is written as a JSON object built from a Zen palette style:

| Key | Required | Meaning |
|---|---|---|
| `style` | Yes | The style description exactly as Zen stores it in a palette: `namedStyle`, `numberFormat` and the `format` object (font, borders, alignment, protection and sizing). |
| `name` | No | The palette style's name, e.g. `number.percentage`. |
| `id` | No | The palette style's id. |
| `palette-id` | No | The id of the palette the style belongs to. |

The `name`, `id` and `palette-id` keys come from the palette style's metadata and let Zen relate the column back to the palette entry it was styled from. The rest of the metadata (title, description, author, creation and modification dates, preview image and preview address) describes the style within its palette and is not written to PSOSF.

Rules:

- The style applies to every data cell in the column (rows 2 onward). Header cells are not styled by line 3.
- A column has at most one style. As in Zen palettes, styles are never combined or layered.
- `style` is authoritative. A converter renders the column from `style` even when the referenced palette style exists and has since changed, so an archive looks the same wherever it is opened. Zen reports when the palette style differs.
- `columnWidth` and `useStandardWidth` set the column's width. `rowHeight` and `useStandardHeight` are ignored, because a row is shared by every column.
- Styling affects only how cells are displayed. The compiler and runtime ignore line 3, and a number format never changes a cell's value.
- Writers serialize the object compactly (no insignificant whitespace) with keys in sorted order at every level, so the same style always produces the same bytes. Non-ASCII characters are written as UTF-8 rather than escaped.
- The field is always quoted, with every double quote inside it doubled.

For example, the `number.percentage` style, shown pretty-printed for reading:

```json
{
  "id": "018c8b12-fc5b-5ff6-9864-6040e897b9f1",
  "name": "number.percentage",
  "palette-id": "5cd77368-ea83-5c67-8e91-9fc4fb075bba",
  "style": {
    "format": {
      "autoIndent": false,
      "borders": [
        {"color": "#000000", "sideIndex": "EdgeTop", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "EdgeBottom", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "EdgeLeft", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "EdgeRight", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "InsideVertical", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "InsideHorizontal", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "DiagonalDown", "style": "None", "weight": "Thin"},
        {"color": "#000000", "sideIndex": "DiagonalUp", "style": "None", "weight": "Thin"}
      ],
      "columnWidth": 63,
      "font": {
        "bold": false, "color": "#64748B", "italic": false, "name": "Arial", "size": 10,
        "strikethrough": false, "subscript": false, "superscript": false,
        "tintAndShade": 0, "underline": "None"
      },
      "horizontalAlignment": "Right",
      "indentLevel": 0,
      "protection": {"formulaHidden": false, "locked": true},
      "readingOrder": "Context",
      "rowHeight": 16.5,
      "shrinkToFit": false,
      "textOrientation": 0,
      "useStandardHeight": false,
      "useStandardWidth": false,
      "verticalAlignment": "Center",
      "wrapText": false
    },
    "namedStyle": "Normal",
    "numberFormat": "0.0%;(0.0%);–"
  }
}
```

In the worksheet file, the same object is a single compact line inside a quoted field, beginning:

```
"{""id"":""018c8b12-fc5b-5ff6-9864-6040e897b9f1"",""name"":""number.percentage"",""palette-id"":
```

### Line 4: Headers

Each field is a column header. Headers must be non-blank and unique within the worksheet. Header text is matched exactly as written, including case and internal spaces.

### Lines 5 onward: Cells

Each line is one worksheet row. Each field is the content of one cell, written exactly as it would be entered in Excel. The compiler interprets these cells exactly as it interprets the same cells in an Excel workbook.

| Content | How to write it | Example |
|---|---|---|
| Blank cell | An empty field | `,,` |
| Text | As is | `corporate` |
| Number | Digits with an optional leading `-` and a `.` decimal point; no thousands separators, currency symbols or percent signs | `-400`, `0.0525` |
| Boolean | `TRUE` or `FALSE` (case-insensitive; uppercase is canonical) | `TRUE` |
| Date | ISO 8601: `YYYY-MM-DD`, or `YYYY-MM-DDTHH:MM:SS` for a date and time. Dates are converted to Excel's numeric date representation | `2026-10-25` |
| Formula | `psosf:` followed by the formula exactly as typed in Excel, starting with `=`. It must be a syntactically valid Excel formula using [supported functions](https://docs.pebblestream.com/docs/supported-functions) | `psosf:=C2<=0` |
| Text that starts with `=`, `+`, `-`, `@`, `'`, a tab, a carriage return or `psosf:` | Prefix with a single apostrophe, as in Excel; the apostrophe is not part of the value | `'=not a formula`, `'+1 555 0100` |

Every field is also subject to [Escaping fields](#escaping-fields), which is what keeps a worksheet file safe to open directly in a spreadsheet application.

### Cell references

Formulas use worksheet coordinates, not file line numbers. The header line (line 4) is row 1 of the worksheet, so line *n* of the file is row *n − 3*:

| File line | Worksheet row |
|---|---|
| 4 (headers) | 1 |
| 5 | 2 |
| 6 | 3 |

Column letters follow header order: the first header is column A, the second column B, and so on. References to other worksheets use Excel syntax, e.g. the formula `psosf:='Account Totals'!B2`.

### Ranges

A Pebble Stream worksheet's row count is decided when it runs, not when it is written: input rows arrive with each execution, and transformative directives produce as many rows as the data requires. A bounded vertical range, such as `B2:B100`, covers only the rows that existed when it was written, and silently ignores the rest. Bounded vertical ranges are therefore almost always a mistake, and the compiler warns on each one. Refer to the whole column instead, e.g. `psosf:=SUM(B:B)`.

### CSV conventions

- UTF-8, without a byte-order mark.
- Lines end with a line feed (LF). Readers also accept CRLF.
- Fields are separated by commas.
- A field containing a comma, a double quote or a line break is enclosed in double quotes, and any double quote inside it is doubled (`""`), per RFC 4180.
- Writers quote only when required, so that the same spreadsheet always produces the same bytes.
- No blank line follows the last row.

### Escaping fields

Excel, LibreOffice and Google Sheets treat a CSV field as a formula and evaluate it when the field begins with `=`, `+`, `-` or `@`, or with a tab or carriage return. PSOSF writes every field so that none begins that way. As a result, any valid PSOSF worksheet file can be opened directly in a spreadsheet application without anything in it being evaluated.

These rules apply to every field in every line of a worksheet file (annotations, types, styles, headers and cells) and to every field of a [test](#tests) CSV file. They apply to the field's value after CSV unquoting: enclosing a field in double quotes does not stop a spreadsheet application from evaluating it.

The **trigger characters** are `=`, `+`, `-`, `@`, the tab character and the carriage return.

#### Writing a field

1. Start from the field's content as it would be typed into an Excel cell. A formula begins with `=`. Text that begins with a trigger character, an apostrophe, or `psosf:` is given a leading apostrophe, as Excel requires for text that could otherwise be misread.
2. If the result begins with a trigger character, prefix it with `psosf:`.

A plain number, written as described for numbers under [Lines 5 onward](#lines-5-onward-cells) (such as `-400` or `-0.0525`), is written as is. Spreadsheet applications read it as a number, which is harmless.

In practice the prefix appears only before formulas, because text that begins with a trigger character already starts with its apostrophe:

| Content | Typed in Excel | Written in PSOSF |
|---|---|---|
| Formula `=C2<=0` | `=C2<=0` | `psosf:=C2<=0` |
| Text `=total` | `'=total` | `'=total` |
| Text `@SUM(A1)` | `'@SUM(A1)` | `'@SUM(A1)` |
| Text `+1 555 0100` | `'+1 555 0100` | `'+1 555 0100` |
| Text `psosf:=x` | `'psosf:=x` | `'psosf:=x` |
| Text `'quoted'` | `''quoted'` | `''quoted'` |
| Number `-400` | `-400` | `-400` |

#### Reading a field

1. If the field begins with `psosf:`, remove the prefix. The rest is a formula and must begin with `=`. The prefix is allowed only in cells (line 5 onward); anywhere else it makes the archive invalid.
2. Otherwise, if the field begins with an apostrophe, remove that one apostrophe. The rest is text.
3. Otherwise, if the field begins with a trigger character, it must be a plain number. Any other field beginning with a trigger character makes the archive invalid.
4. Otherwise, read the field as its line and column type require.

#### Invalid fields are rejected, never repaired

A reader that finds an invalid field rejects the archive and names the field and both ways to fix it:

> `Accounts.csv`, line 7, column C (`amount`): a field may not begin with `@`. Write `psosf:=@SUM(A1)` if it is a formula, or `'@SUM(A1)` if it is text.

A reader never guesses which was meant. Either reading could be right (Excel accepts `@SUM(A1)` typed into a cell as a formula), and an unescaped field is exactly what a spreadsheet application would evaluate if the file were opened directly. A conforming writer never produces one, so its presence means a faulty writer, a hand edit or tampering, which someone should see. Correcting a field is a separate, explicit step in which a person or agent states which reading was meant.

Every Pebble Stream reader applies these rules: Zen's import, the validate endpoint and the compiler service. An archive with an invalid field is therefore never converted, compiled or deployed.

---

## Example

`Accounts.csv`:

```
Account identifier from the general ledger.,"pebblestream:not-blank
Customer classification: individual or corporate.",Balance in US dollars.,"pebblestream:fail
Stops the run when a balance is zero or negative."
text,text,number,boolean
,,,
id,type,amount,amount is not positive
1,individual,435,psosf:=C2<=0
2,corporate,-400,psosf:=C3<=0
```

Read as a worksheet:

| | A | B | C | D |
|---|---|---|---|---|
| **Note** | Account identifier from the general ledger. | `pebblestream:not-blank`<br>Customer classification: individual or corporate. | Balance in US dollars. | `pebblestream:fail`<br>Stops the run when a balance is zero or negative. |
| **Type** | text | text | number | boolean |
| **1** | **id** | **type** | **amount** | **amount is not positive** |
| **2** | 1 | individual | 435 | `=C2<=0` |
| **3** | 2 | corporate | -400 | `=C3<=0` |

The formulas in column D are written with the `psosf:` prefix; the table shows them as they appear in Excel. Column A carries a comment only; columns B and D carry a directive followed by a comment; column C's comment documents its units.

---

## Converting between Excel and PSOSF

A PSOSF archive and an Excel workbook can represent the same spreadsheet. This section defines how a conforming converter, such as the Zen add-in, moves between them in each direction, and where the manifest lives inside a workbook.

### Where each part lives in Excel

| PSOSF | Excel |
|---|---|
| Worksheet file | Worksheet of the same name |
| Worksheet order in `sheets` | Tab order |
| Line 1 (annotations) | The note on each header cell, verbatim |
| Line 2 (types) | Recorded in the manifest part (see below); the cell values in that column are written as that type |
| Line 3 (formatting) | The formatting of the column's data cells |
| Line 4 (headers) | Row 1 cell values |
| Line *n* (*n* ≥ 5) | Row *n − 3* cell values and formulas |
| `manifest.json` | The PSOSF manifest custom XML part |
| `tests/` | The PSOSF tests custom XML part |
| `signatures.json` | The PSOSF signatures custom XML part |

### The manifest in a workbook

A workbook stores the manifest in a custom XML part inside the `.xlsx` package. Custom XML parts are not shown anywhere in Excel's interface and cannot be deleted through it, so the manifest survives ordinary editing, saving, and renaming or reordering of sheets.

The part's root element is in the namespace `urn:pebblestream:psosf`:

```xml
<psosf:manifest xmlns:psosf="urn:pebblestream:psosf" format-version="1.1">
  <psosf:json>{ ...the contents of manifest.json... }</psosf:json>
  <psosf:column-types>{ ...column types, see below... }</psosf:column-types>
</psosf:manifest>
```

- `psosf:json` holds the manifest exactly as it appears in `manifest.json`, as XML-escaped text.
- `psosf:column-types` holds each worksheet's line 2, as a JSON object keyed by worksheet name and then header:

  ```json
  {
    "Accounts": {
      "id": "text",
      "type": "text",
      "amount": "number",
      "amount is not positive": "boolean"
    }
  }
  ```

  Storing the types here keeps them for columns that have no data rows, and keeps a text column from being reinterpreted by Excel.

Tests, when present, are stored in a second custom XML part whose root element is `psosf:tests` in the same namespace. Each test is a `psosf:test` element with a `name` attribute, holding its `test.json` and each of its input and expected CSV files as XML-escaped text:

```xml
<psosf:tests xmlns:psosf="urn:pebblestream:psosf">
  <psosf:test name="zero-balance">
    <psosf:json>{ ...test.json... }</psosf:json>
    <psosf:input sheet="Accounts">...CSV...</psosf:input>
    <psosf:expected sheet="Account Totals">...CSV...</psosf:expected>
  </psosf:test>
</psosf:tests>
```

A workbook has at most one manifest part and at most one tests part. Converters find them by namespace and root element name; finding more than one of either is an error.

### Compact and full export

A workbook can be exported in two modes. **Compact** is the default.

- **Compact** keeps everything that defines the model (every worksheet, header, annotation, type, style and formula) but only the rows needed to represent it. Rows that are data rather than logic are left out, so the archive stays small enough to review, sign and pass to an agent.
- **Full** keeps every row of every worksheet.

A compact export applies two rules:

- **Generated worksheets** (those with a transformative or generative directive, whose rows are produced when the worksheet runs) keep their data rows only up to and including the drag-down row: the row whose formulas are filled down to the generated rows. Rows below it are produced at run time and are not written. A generated worksheet with no formulas keeps only its header.
- **Input worksheets** (all others) keep their first data rows, up to the input row limit (default 100). If any formula or directive argument anywhere in the spreadsheet refers to a row beyond the limit, rows are kept through the highest row referred to, so that every reference still points at the same cell.

The manifest's `export` field records the mode and the input row limit. Exporting again with the same settings reproduces the archive exactly, so a compact archive survives a round trip through Excel.

Compact export drops data, not logic. The dropped rows cannot be recovered from the archive; use full export when the archive must carry a workbook's complete data.

### Excel to PSOSF

For each worksheet, in tab order:

1. **Skip worksheets that are not compiled.** A worksheet carrying `pebblestream:do-not-compile` is not exported. Chart sheets are not exported.
2. **Find the rectangle.** Headers are the contiguous non-blank cells of row 1, starting at A1. Data rows run from row 2 to the last row with any content within the header columns. Missing headers and blank columns are reported as the compiler reports them; the converter does not guess. In a compact export, the data rows are then reduced as [Compact and full export](#compact-and-full-export) describes, and the number of rows dropped from each worksheet is reported.
3. **Write line 1** from each header cell's note, verbatim, with line endings normalized to LF. A header cell without a note gets a blank field.
4. **Write line 2** from the manifest part's `column-types` for that worksheet and header. A column with no entry gets the type the compiler determines for it.
5. **Write line 3.** If every data cell in a column has the same formatting, write it as a style, with the column's width as `columnWidth`. When the cells were styled from a Zen palette style, include its `name`, `id` and `palette-id`. If the column has no data rows, or its cells are formatted differently from one another, write a blank field and report the column.
6. **Write line 4** from the row 1 values.
7. **Write each data row** as one line:
   - A formula cell is written as `psosf:` followed by its formula in en-US form: A1 references, English function names and comma argument separators. Converters read the formula, not its localized display form.
   - A number is written in the shortest form that reads back as the same value.
   - A boolean is written as `TRUE` or `FALSE`.
   - In a column of type `date`, a numeric value is written as an ISO 8601 date, with a time when it has a fractional part, using the workbook's date system (1900 or 1904).
   - Text is written as is, with a leading apostrophe when [Escaping fields](#escaping-fields) requires one.
   - An empty cell is written as an empty field.

   Annotations, headers and every other field are escaped by the same rules.

Then write `manifest.json`:

- If the workbook has a manifest part, start from its `psosf:json`.
- Replace `sheets` with the exported worksheets in tab order. The sheet list always comes from the workbook, never from the stored manifest.
- Set `export` to the mode and input row limit used. For a full export, `export` is omitted.
- Write the result in the canonical JSON form. If the workbook's sheets have not changed, this reproduces the stored manifest exactly.
- If there is no manifest part, set `name` from the workbook's file name, leave `namespace` and `description` absent, and report that the manifest was missing.

Entries in `column-types` for worksheets or headers that no longer exist are discarded. If the workbook has a tests part, write each test to `tests/`. If the workbook has a signatures part, write its content to `signatures.json` unchanged, even if the workbook has been edited since it was signed, so that verification reports the change rather than hiding it.

Anything the converter drops is listed in its report, with locations: cells outside a worksheet's rectangle, notes on cells other than header cells, threaded comments, worksheets skipped for `pebblestream:do-not-compile`, and chart sheets.

### PSOSF to Excel

Before writing anything, the converter checks the whole archive. An archive with an invalid field, or one that breaks any rule under [Security](#security), is refused and no workbook is produced.

1. Create one worksheet per entry in `sheets`, in that order, with the same name. New workbooks use the 1900 date system.
2. Write each header in row 1, and attach the column's line 1 annotation to the header cell as a note, verbatim once its escaping is removed. A blank annotation adds no note.
3. Write each data line into the corresponding row, reading each field as [Escaping fields](#escaping-fields) describes and interpreting it by its column's line 2 type:
   - A field starting with `psosf:` is written as a formula, without the prefix.
   - A field starting with `'` is written as text, without the apostrophe.
   - In a `text` column, every other field is written as text, so Excel never converts values such as `00123`, `1/2` or `TRUE`.
   - In a `number`, `boolean` or `date` column, the field is written as that type; dates become Excel date serial numbers and are given a date number format so they display as dates.
   - A field that does not parse as its column's type is written as text and reported.
   - An empty field leaves the cell empty.
4. Write the manifest part, with `psosf:json` holding `manifest.json` and `psosf:column-types` holding every worksheet's line 2.
5. If the archive has tests, write the tests part.
6. If the archive has `signatures.json`, write it unchanged to the signatures part.

Each column's line 3 style, if any, is applied to the data cells in that column, and its `columnWidth` sets the column's width. Header cells and columns without a style are left unformatted.

### Round trips

- **PSOSF → Excel → PSOSF** produces an identical archive, file for file. Signatures therefore survive a round trip through Excel, provided the workbook is not edited.
- **Excel → PSOSF → Excel** with a full export preserves everything the compiler reads (notes on header cells, headers, values, formulas, column types), the manifest, and the formatting of columns whose data cells are formatted uniformly. Header formatting, formatting that varies within a column, the widths of unstyled columns, row heights, charts, notes on other cells, threaded comments, content outside each worksheet's rectangle and `pebblestream:do-not-compile` worksheets are not carried. A compact export also leaves out the rows it drops, while keeping every formula, directive, header, type and style.

### When the manifest is missing

The manifest part can still be lost: Excel's Document Inspector offers to remove custom XML data, some other applications drop custom XML parts on save, and copying worksheets into a new workbook leaves the part behind because it belongs to the workbook. When Zen opens a workbook that contains PebbleScript but has no manifest part, it offers to create one: the sheet list and column types are derived from the workbook, the name defaults to the file name, and the user supplies the namespace and description.

### Google Sheets

Zen for Google Sheets stores the same `psosf:manifest`, `psosf:tests` and `psosf:signatures` content as spreadsheet-level developer metadata, which is hidden from the Sheets interface and accessible only through the API. Conversion otherwise follows the rules above.

---

## Tests

An archive may include tests that the compiler service runs against the compiled spreadsheet. Each test is a folder under `tests/`, named for the test.

```
tests/<test name>/
├── test.json
├── inputs/<worksheet name>.csv
└── expected/<worksheet name>.csv
```

- `inputs/` holds a CSV file for each input worksheet the test supplies.
- `expected/` holds a CSV file for each output worksheet the test checks. Worksheets without an expected file are not checked.
- Test CSV files are plain CSV: one header line followed by values. They have no annotation, type or formatting lines. Values follow the same rules and the same [escaping](#escaping-fields) as worksheet cells, but formulas are not allowed, so a `psosf:` field in a test file makes the archive invalid.

`test.json`:

```json
{
  "description": "A zero balance stops the run.",
  "expect": "fail",
  "message": "amount is not positive",
  "ordered": false,
  "tolerance": 0.000001
}
```

| Field | Required | Meaning |
|---|---|---|
| `description` | No | What the test checks. |
| `expect` | Yes | `pass` (the run completes and the expected outputs match), `warn` (as `pass`, and at least one warning is raised) or `fail` (the run stops on a `fail` or validation directive). |
| `message` | No | For `warn` or `fail`, the header of the column that must raise it. |
| `ordered` | No | When `true`, expected rows must appear in the same order. Defaults to `false`, since workflow-each and workflow-group do not preserve row order. |
| `tolerance` | No | Absolute tolerance for comparing numbers. Defaults to exact comparison. |

---

## Security

PSOSF is designed so that a spreadsheet can be read, reviewed and run without trusting whoever produced it. A PSOSF spreadsheet, and any Excel workbook built from one by Pebble Stream tooling, makes three guarantees:

1. **No executable code.** It contains no VBA, macros or embedded programs.
2. **Nothing reaches outside the spreadsheet on its own.** No formula, link, connection or object can fetch data, contact another process, or open another file.
3. **Everything is reviewable.** All logic is plain text: formulas, PebbleScript, types and styles.

[Signatures](#signatures) add a fourth: evidence of who wrote and approved the content, and that it has not changed since. A signature says nothing about whether content is safe. A signed archive is checked against every rule in this section exactly as an unsigned one is.

### No executable code

PSOSF has no place to put code. A worksheet file holds values, formulas and annotations, and the archive may contain only the files listed under [Archive structure](#archive-structure). There is no equivalent of Excel's `vbaProject.bin`, no macro sheets and no embedded objects.

When converting PSOSF to Excel, converters write a macro-free `.xlsx` workbook, never `.xlsm`, `.xlsb` or `.xltm`, and verify that the result contains no `vbaProject.bin` part. The no-VBA guarantee is then something anyone can check by inspecting the package, not only a promise.

### Formulas: an allow-list

Formulas may use only the functions on the [supported functions](https://docs.pebblestream.com/docs/supported-functions) list. Any other function is refused, by name, wherever it appears in a formula.

The list is an allow-list, not a deny-list of dangerous functions. A deny-list has to anticipate every way out, and fails open:

- Blocking two functions by name leaves the rest of the family open. `FILTERXML` usually travels with `WEBSERVICE`, `IMAGE` fetches a URL, `HYPERLINK` can carry data out in the address it builds, and `RTD` and DDE (`=app|topic!item`) talk to other processes on the machine.
- Microsoft keeps adding functions. A new function that reaches outside the workbook would be allowed until someone noticed and added it.

With an allow-list, anything not explicitly supported is refused by default. `WEBSERVICE` and `HYPERLINK` are not banned so much as never admitted, and a function Microsoft releases next year is refused until Pebble Stream deliberately adds it.

The same check covers formula syntax that reaches outside the spreadsheet without naming a function:

| Construct | Example | Refused because |
|---|---|---|
| DDE | `=app\|topic!item` | Talks to another process |
| External workbook reference | `=[Other.xlsx]Sheet1!A1`, `='C:\data\[Rates.xlsx]Q1'!B2` | Opens or reads another file |
| URL or path in a reference | `='https://example.com/[Book.xlsx]Sheet1'!A1` | Fetches over the network |

Functions that build a reference from text, such as `INDIRECT`, are not supported. No formula can therefore assemble a reference to another workbook, a path or a URL at calculation time; every reference outside the worksheet's own cells appears in the formula itself, where the checks above can see it.

### What isn't a formula

A workbook can reach outside itself without any formula at all. Each of these channels is refused, or the guarantees above have holes:

- **Cell hyperlinks** inserted through Excel's Insert Link menu. They are stored in the worksheet's hyperlink records, separately from formulas, so a formula check alone never sees them.
- **External workbook references** outside cell formulas: links recorded in the workbook's external-links parts, and references in defined names, data validation rules, conditional formatting rules and chart series.
- **Data connections and Power Query** queries, including connections to databases, web pages, OData feeds and other files.
- **OLE and embedded objects**, including linked objects whose content is refreshed from another file.
- **XLM macro sheets** (Excel 4.0 macros), which are already excluded because PSOSF carries only plain worksheets.

PSOSF itself cannot represent most of these: it has no hyperlink records, connections, objects or macro sheets. The rule matters at the boundaries where a workbook enters Pebble Stream, so a workbook carrying any of them is refused there rather than silently converted without them, since silently dropping a connection could change what the spreadsheet computes.

### PebbleScript

Directive arguments that are [cell references](#cell-references) must refer to cells inside the spreadsheet. A reference to another workbook, a URL or a path is refused.

Connector directives (`http`, `sql`, `files`, `queue`, `email`) are the one sanctioned way for a Pebble Stream spreadsheet to exchange data with other systems. Unlike the channels above, they are declared in PebbleScript where a reviewer can see them, they run only in the Pebble Stream connector executor rather than in Excel, and they act only through connections and credentials the user has set up. Opening a workbook that contains a connector directive does nothing; the directive runs only when Pebble Stream executes the spreadsheet.

### Values that look like formulas

Spreadsheet formula injection relies on a field that a spreadsheet application treats as a formula, one beginning with `=`, `+`, `-` or `@`. A worksheet file is a CSV file, and Excel will open one directly, outside any Pebble Stream check.

[Escaping fields](#escaping-fields) closes this path. In a valid PSOSF archive, no field in any worksheet or test file begins with a trigger character, except plain numbers: formulas carry the `psosf:` prefix, and text that could be misread carries a leading apostrophe. Opening a valid worksheet file directly in Excel, LibreOffice or Google Sheets therefore evaluates nothing. Formulas, once converted into a workbook, are checked against the allow-list like any other.

Converters also write every value into a workbook through Excel's value interface, by the column's declared type, never as typed input. So `'+cmd`, `'-1+1` or `'@SUM(A1)` in a text column becomes the text `+cmd`, `-1+1` or `@SUM(A1)` in the workbook and is never evaluated.

What escaping cannot do is protect a file that breaks the rules. An archive written without Pebble Stream tooling, by an agent or by hand in a text editor, may contain an unescaped field. Every Pebble Stream reader rejects such an archive, so it is never converted, compiled or deployed, but nothing stops someone from opening its worksheet files directly before any reader has seen them. Three practices keep that exposure small:

- **Generate archives with Pebble Stream tooling.** An agent that writes archives through a library or tool, from cell values and PebbleScript JSON Notation, never writes a field by hand and so never produces an unescaped one.
- **Validate before sharing.** The validate endpoint reports every invalid field with its fix, so an archive built without tooling is caught on its first submission rather than by the person who opens it.
- **Sign validated archives.** An archive that is shared or promoted is signed after it validates, so a hand-built, unvalidated archive is visibly different from one that came through the pipeline.

### Reading archives

Readers treat every archive as hostile input:

- Read entries into memory from the ZIP central directory. Never extract to disk.
- Reject an archive containing absolute paths, `..` segments, backslashes, drive letters, symbolic links, duplicate names, names that differ only in case, overlapping entries, or data before the first local file header.
- Reject names that are not valid UTF-8, contain control characters, end in a dot or a space, or match a reserved Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM1` to `COM9`, `LPT1` to `LPT9`).
- Cap the number of entries, the size of each entry, the total uncompressed size and each entry's compression ratio, and stop reading as soon as a cap is reached.
- Reject any file the manifest and the archive structure do not account for.

The same caution applies to the `.xlsx` side, which is also a ZIP archive of XML parts. Every XML parser that reads a workbook, including the parser for the PSOSF custom XML parts, disables document type declarations and external entities, so a crafted part cannot read local files or make network requests through XML external entity (XXE) expansion.

### Where the rules are enforced

Zen runs inside Excel, so by the time Zen sees a workbook, Excel has already opened it and may have recalculated it. What happens at that moment is governed by Excel's own Protected View, Trust Center and external-content settings, not by Pebble Stream. Zen can refuse to process a workbook; it cannot stop Excel from opening one.

Pebble Stream enforces the rules at every point where a spreadsheet crosses into or out of its tooling:

| Point | What happens |
|---|---|
| Reading a PSOSF archive (Zen's import, the validate endpoint, the compiler service) | An archive with an invalid field is refused, with each field named. |
| Zen validate and compile | The workbook is refused, with each offending cell and the function, construct or channel named, so the pebbler can fix it. |
| Submission to Flow | Zen refuses to send a workbook that fails the rules. |
| PSOSF to Excel conversion | A PSOSF archive containing a refused function or construct is refused, and converters never emit one into a workbook. |
| Excel to PSOSF conversion | Import fails if the workbook contains a refused function, construct or channel, naming each one. |
| Compiler service | The compiler checks every archive and workbook it receives, whoever sent it. |

The last row matters most for agents. An agent may submit a PSOSF archive straight to the compiler service without passing through Zen, so the service never relies on a check made elsewhere. Every point applies the same allow-list, from the same source, so a spreadsheet accepted at one point is never refused at another.

Together, these turn the rules into a guarantee for anything built by Pebble Stream tooling, and escaping extends it to opening any valid worksheet file directly. Files that break the rules, and workbooks produced elsewhere and opened directly in Excel, remain the responsibility of Excel's own protections.

---

## Signatures

An archive may carry signatures that make it tamper-evident and record who wrote and approved it. Any change to the archive's content after signing (a cell, an annotation, a style, a test, an added or removed worksheet) invalidates every signature. Unsigned archives remain valid; whether a signature is required is a matter of policy, such as a promotion gate that requires approval before a pebble is deployed.

Signatures provide integrity and provenance, not confidentiality. A signed archive is still plain text. Logic that must stay sealed, such as a marketplace workflow sold without its source, is distributed as a compiled pebble, never as PSOSF.

### The digest list

Signatures are made over a digest list rather than the ZIP file, so recompressing or reordering the archive does not invalidate them.

The digest list has one line for every file in the archive except `signatures.json`: the file's SHA-256 digest in lowercase hexadecimal, two spaces, and its path within the archive. Lines are sorted by path, comparing UTF-8 bytes, and each ends with a line feed.

```
3f1c…e09a  Account Totals.csv
9b42…71d5  Accounts.csv
c07e…a3b8  manifest.json
5d19…0c4f  tests/zero-balance/expected/Account Totals.csv
e8a0…94b2  tests/zero-balance/inputs/Accounts.csv
1a6d…f7e3  tests/zero-balance/test.json
```

Digests are shown shortened here; in an archive each is 64 hexadecimal characters.

The SHA-256 digest of the digest list itself is the **archive digest**. It identifies the archive's content in one value.

### signatures.json

```json
{
  "digests": [
    {
      "path": "Account Totals.csv",
      "sha-256": "3f1c…e09a"
    },
    {
      "path": "Accounts.csv",
      "sha-256": "9b42…71d5"
    }
  ],
  "format": "psosf-signatures",
  "format-version": "1",
  "signatures": [
    {
      "algorithm": "ed25519",
      "key-id": "gsh-org-7f3a/agents/svc-ledger-builder/2026-10",
      "role": "author",
      "signature": "q8Zt…3wDg==",
      "signed-at": "2026-10-25T14:30:00Z",
      "signer": {
        "id": "svc-ledger-builder",
        "kind": "agent",
        "model": "example-model-name"
      }
    },
    {
      "algorithm": "ed25519",
      "key-id": "gsh-org-7f3a/users/a.mensah/2026-09",
      "role": "approver",
      "signature": "Vb1L…kA8w==",
      "signed-at": "2026-10-25T16:05:12Z",
      "signer": {
        "id": "a.mensah@example.com",
        "kind": "human"
      }
    }
  ]
}
```

(The `digests` list is shortened to two entries here; it lists every file the digest list covers. A real signature is 64 bytes, 88 characters in Base64.)

| Field | Meaning |
|---|---|
| `format` | Always `psosf-signatures`. |
| `format-version` | The version of these rules, `1`. |
| `digests` | The digest list as JSON: every file except `signatures.json`, sorted by path. It must agree with the digest list computed from the archive. |
| `signatures` | One or more signatures over the digest list. |
| `signatures[].algorithm` | Always `ed25519`. |
| `signatures[].key-id` | Identifies the signer's public key. |
| `signatures[].role` | `author` (wrote or generated the content) or `approver` (reviewed and approved it). |
| `signatures[].signer` | Who signed: `kind` is `human` or `agent`; `id` is the user or service identity; `model` names the model when `kind` is `agent`. |
| `signatures[].signed-at` | ISO 8601 UTC timestamp of signing. |
| `signatures[].signature` | The Ed25519 signature, Base64-encoded. |

### What each signature covers

Each signature is made over a message that binds the signer, role and time to the content, so a signature cannot be relabeled (an author's signature presented as an approval, say) without invalidating it. The message is UTF-8 text:

```
psosf-signature 1
algorithm: ed25519
key-id: gsh-org-7f3a/agents/svc-ledger-builder/2026-10
role: author
signer-kind: agent
signer-id: svc-ledger-builder
signer-model: example-model-name
signed-at: 2026-10-25T14:30:00Z
digests:
3f1c…e09a  Account Totals.csv
9b42…71d5  Accounts.csv
…
```

Each line ends with a line feed. The `signer-model` line appears only when the signature has a `model`. The lines after `digests:` are the digest list exactly.

### Signing

1. Write every other file in its final form.
2. Compute the digest list.
3. Build the message for this signature and sign it with the signer's Ed25519 private key.
4. Add the signature to `signatures.json`, creating it if needed with the `digests` list.

Signing never changes any other file, so an approver adds a signature to an archive the author has already signed, over the same digest list.

### Verifying

1. Compute the digest list from the archive's files.
2. Check that it agrees with `digests` in `signatures.json`: the same files, the same digests. Any file added, removed or changed since signing makes the archive fail verification.
3. For each signature, rebuild its message, resolve `key-id` to a public key the verifier trusts, and check the signature.
4. Report each signature as valid or invalid, with its role and signer.

A verifier reports a failed check; it never removes signatures. An archive that no longer verifies keeps its signatures as evidence of what was signed.

### Approval policy

Signatures let an archive carry its own approval record. Pebble Stream promotion gates can require, for example, an `approver` signature by a human whose identity differs from every `author` signer before the compiled pebble moves to QA or production. Because author and approver sign the same digest list, the approval provably covers exactly what the author produced.

### The compiled pebble

When the compiler builds a pebble from a PSOSF archive, it records the archive digest in the compiled pebble. A deployed pebble can then be traced to the exact archive it was built from, and that archive's signatures show who wrote and approved it.

### Signatures in a workbook

A workbook stores `signatures.json` in a third custom XML part, `psosf:signatures`, in the same namespace as the manifest. The text of the part is `signatures.json`, unchanged.

When Zen opens a signed workbook, it builds the workbook's PSOSF form, computes the digest list and verifies the signatures. Because converting PSOSF to Excel and back is exact, an unedited workbook verifies. Any edit to the workbook's content invalidates the signatures; Zen shows that the workbook has been modified since it was signed and offers to sign again.

---

## Versioning

The manifest's `format-version` tells readers which rules apply.

| Version | Identified by | Differences |
|---|---|---|
| 1.0 | No `manifest.json` in the archive | Worksheet order is the order of files in the archive. Line 1 is read with the same annotation rules as 1.1. Line 3 must be blank. No tests. A cell beginning with `=` is a formula, and only text beginning with `=` or `'` is escaped, so other fields beginning with a trigger character are read as text. Readers convert 1.0 archives safely, but their worksheet files are not safe to open directly in a spreadsheet application. |
| 1.1 | `"format-version": "1.1"` | This page. |

Readers reject archives with a `format-version` they do not support, rather than guessing.

---

## Notes for agents

When generating a PSOSF archive:

1. Write `manifest.json` first, listing every worksheet in `sheets`.
2. Give every line of a worksheet file exactly as many fields as there are headers. In line 3, leave a field blank for an unstyled column (for four unstyled columns, `,,,`). To style a column, copy a style from a palette; never invent a preview or other palette metadata.
3. Quote any field that contains a comma, a double quote or a line break, and double every quote inside it. Annotations with directives almost always need quoting, because directive arguments are quoted strings.
4. Remember that PebbleScript is declarative: transformative directives run in a fixed order, not the order they are written. See [PebbleScript JSON Notation](https://docs.pebblestream.com/docs/pebblescript-json-notation#pebblescript-is-declarative) for the order.
5. Put each directive on its own line within the annotation, starting with `pebblestream:`. Any other line in the annotation is a comment. Write worksheet-level directives in the first column's annotation.
6. Write cell references in worksheet coordinates: the first data line (line 5) is row 2.
7. Write dates as `YYYY-MM-DD`, booleans as `TRUE`/`FALSE`, and numbers without separators or symbols.
8. Write every formula as `psosf:` followed by the formula, as in `psosf:=C2<=0`. Give any text that begins with `=`, `+`, `-`, `@`, `'`, a tab, a carriage return or `psosf:` a leading apostrophe. This applies to headers and annotations too. Prefer Pebble Stream tooling for writing archives, which applies these rules for you.
9. Use only [supported functions](https://docs.pebblestream.com/docs/supported-functions) in formulas, and refer to whole columns (`B:B`) rather than bounded ranges (`B2:B100`). Never write external workbook references, URLs or paths in formulas or directive arguments. To exchange data with another system, use a connector directive. See [Security](#security).
10. Include tests for the behavior that matters, especially the cases that should `fail`.
11. Submit the archive to the compiler service's validate endpoint and fix every reported error before deploying.
