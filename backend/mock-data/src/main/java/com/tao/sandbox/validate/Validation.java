package com.tao.sandbox.validate;

import java.util.List;

/**
 * What was checked about a payload, and what it said.
 *
 * @param checked never optional. A payload that merely parses must never display as plainly
 *     "valid" — a schema-invalid mock would look clean, and validation that quietly checks less
 *     than the reader assumes is worse than none at all.
 * @param completeness percentage of schema-declared fields populated, or null when nothing
 *     declared any
 */
public record Validation(boolean valid, Checked checked, Integer completeness, List<Issue> issues) {

    public enum Checked {
        /** Parsed and checked against the contract's schema. */
        SCHEMA,
        /** Parsed only — the contract declares no schema, or none can be extracted for it yet. */
        SYNTAX,
        /** Not checked at all; no parser applies to this payload. */
        NONE
    }

    /** @param line null when the underlying parser reports no position, which schema checks do not */
    public record Issue(String path, Integer line, String message, String rule) {}

    static Validation clean(Checked checked, Integer completeness) {
        return new Validation(true, checked, completeness, List.of());
    }
}
