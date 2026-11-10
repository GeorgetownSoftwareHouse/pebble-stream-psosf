// Copyright 2026 Georgetown Software House, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.pebblestream.psosf;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Java entry points for the PSOSF library.
 *
 * <p>Every method returns JSON text in the shape documented in {@code psosf.api}:
 * classification results have {@code level} ({@code "invalid"}, {@code "valid"} or
 * {@code "acceptable"}), {@code errors}, {@code warnings} and {@code acceptance-errors}.
 * Callers never handle Clojure types.
 */
public final class Psosf {

    private static final IFn CLASSIFY;
    private static final IFn CLASSIFY_FILE;
    private static final IFn CLASSIFY_JSON;
    private static final IFn READ_JSON;
    private static final IFn WRITE_JSON;
    private static final IFn TO_JSON;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("psosf.api"));
        require.invoke(Clojure.read("psosf.json"));
        CLASSIFY      = Clojure.var("psosf.api", "classify");
        CLASSIFY_FILE = Clojure.var("psosf.api", "classify-file");
        CLASSIFY_JSON = Clojure.var("psosf.api", "classify-json");
        READ_JSON     = Clojure.var("psosf.api", "read-json");
        WRITE_JSON    = Clojure.var("psosf.api", "write-json");
        TO_JSON       = Clojure.var("psosf.json", "write-compact");
    }

    private Psosf() {}

    private static Object options(Collection<String> allowedFunctions) {
        if (allowedFunctions == null) {
            return PersistentHashMap.EMPTY;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("allowed-functions", PersistentVector.create(allowedFunctions));
        return PersistentHashMap.create(m);
    }

    /** Classifies a .psosf file, with the library's built-in function allow-list. */
    public static String classifyFile(String path) {
        return classifyFile(path, null);
    }

    /** Classifies a .psosf file against the given function allow-list. */
    public static String classifyFile(String path, Collection<String> allowedFunctions) {
        return (String) TO_JSON.invoke(CLASSIFY_FILE.invoke(path, options(allowedFunctions)));
    }

    /** Classifies an archive given as archive path to file text. */
    public static String classify(Map<String, String> entries, Collection<String> allowedFunctions) {
        Object m = PersistentHashMap.create(entries == null ? Collections.emptyMap() : entries);
        return (String) TO_JSON.invoke(CLASSIFY.invoke(m, options(allowedFunctions)));
    }

    /** {@code {"entries": {...}, "options": {...}}} to a classification result. */
    public static String classifyJson(String requestJson) {
        return (String) CLASSIFY_JSON.invoke(requestJson);
    }

    /** {@code {"entries": {...}}} to {@code {"archive": ..., "errors": ..., "warnings": ...}}. */
    public static String readJson(String requestJson) {
        return (String) READ_JSON.invoke(requestJson);
    }

    /** {@code {"archive": {...}}} to {@code {"entries": {...}}}. */
    public static String writeJson(String requestJson) {
        return (String) WRITE_JSON.invoke(requestJson);
    }
}
