// Copyright 2026 Georgetown Software House, Inc.
// SPDX-License-Identifier: Apache-2.0
//
// Types for @pebblestream/psosf. The implementation is ClojureScript; these
// declarations describe the JSON-shaped contract defined in psosf.api.

/** Archive path to file text, e.g. { "manifest.json": "...", "Accounts.csv": "..." }. */
export type Entries = Record<string, string>;

export type Level = "invalid" | "valid" | "acceptable";

export interface Finding {
  code: string;
  message: string;
  file?: string;
  line?: number;
  /** The header of the column the finding refers to. */
  column?: string;
  fix?: string;
}

export interface Content {
  kind: "text" | "formula";
  /** For a formula, the formula text beginning with "=". */
  text: string;
}

export interface AnnotationLine {
  kind: "directive" | "comment";
  text: string;
}

export interface Column {
  header: string;
  type: "text" | "number" | "boolean" | "date";
  annotation: AnnotationLine[];
  /** The line 3 style object, or null. */
  style: Record<string, unknown> | null;
}

export interface Sheet {
  name: string;
  columns: Column[];
  rows: Content[][];
}

export interface Test {
  json: Record<string, unknown> | null;
  inputs: Record<string, string>;
  expected: Record<string, string>;
}

export interface Archive {
  version: "1.0" | "1.1";
  manifest: Record<string, unknown> | null;
  sheets: Sheet[];
  tests: Record<string, Test>;
  signatures: string | null;
}

export interface Options {
  /** Excel functions Pebble Stream accepts; compared without regard to case. */
  "allowed-functions"?: string[];
}

export interface ClassifyResult {
  level: Level;
  errors: Finding[];
  warnings: Finding[];
  /** Why a valid archive is not acceptable. */
  "acceptance-errors": Finding[];
  archive: Archive;
}

export interface ReadResult {
  archive: Archive;
  errors: Finding[];
  warnings: Finding[];
}

export interface Limits {
  maxEntries?: number;
  maxEntryBytes?: number;
  maxTotalBytes?: number;
  maxRatio?: number;
}

export interface UnzipResult {
  entries: Entries;
  errors: Finding[];
}

/** Classifies an archive as invalid, valid or acceptable. */
export function classify(entries: Entries, options?: Options): ClassifyResult;
/** Reads an archive into data. */
export function read(entries: Entries): ReadResult;
/** Writes an archive in canonical form, as format version 1.1. */
export function write(archive: Archive): Entries;
/** Reads a ZIP archive into entries, in memory, with size and ratio limits. */
export function unzip(bytes: Uint8Array, limits?: Limits): UnzipResult;
/** Writes entries to a ZIP archive, in path order. */
export function zip(entries: Entries): Uint8Array;
/** Unzips, then classifies. */
export function classifyZip(bytes: Uint8Array, options?: Options): ClassifyResult;
