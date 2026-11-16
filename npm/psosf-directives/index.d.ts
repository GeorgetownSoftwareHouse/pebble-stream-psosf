// Copyright 2026 Georgetown Software House, Inc.
// SPDX-License-Identifier: Apache-2.0
//
// Types for @pebblestream/psosf-directives. The implementation is
// ClojureScript, compiled from pebblestream.directives.check.

/** Archive path to file text, e.g. { "manifest.json": "...", "Accounts.csv": "..." }. */
export type Entries = Record<string, string>;

export interface Options {
  /** Excel functions Pebble Stream accepts; compared without regard to case. */
  "allowed-functions"?: string[];
}

export interface Finding {
  /** A stable identifier, safe to match on, e.g. "unknown-header". */
  code: string;
  /** Written for people; may change between versions. */
  message: string;
  /** The worksheet the finding is on. */
  sheet?: string;
  /** The header of the column the finding refers to. */
  column?: string;
  /** The directive line the finding is about, as written. */
  directive?: string;
  /** The header argument that matched no header. */
  argument?: string;
  /** A suggested fix, when one is known. */
  fix?: string;
  /** For structural findings: the archive file and line. */
  file?: string;
  line?: number;
}

export interface Report {
  /** True when the archive is well-formed, breaks no rule, and has no acceptance errors other than a missing allow-list. */
  passed: boolean;
  level: "invalid" | "valid" | "acceptable";
  errors: Finding[];
  warnings: Finding[];
  /** Why a valid archive is not acceptable. */
  "acceptance-errors": Finding[];
}

export type Argument =
  | string
  | number
  | { ref: string }
  | { column: string; order: "asc" | "desc" }
  | { operator: string; column: string }
  | { operator: string; ref: string };

export type ParsedLine =
  | {
      directive: string;
      syntax: "bare" | "paren" | "colon";
      args: Argument[];
      warnings: { code: string; message: string; index: number }[];
    }
  | { error: string; text: string };

/** Checks an archive given as entries. */
export function check(entries: Entries, options?: Options): Report;
/** Unzips an archive in memory, then checks it. */
export function checkZip(bytes: Uint8Array, options?: Options): Report;
/** Parses one line of textual PebbleScript. */
export function parseLine(text: string): ParsedLine;
