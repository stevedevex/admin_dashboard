package com.tao.sandbox.store;

import java.time.Instant;

/** Metadata for browsing. Deliberately excludes the body, which may be megabytes. */
public record MockSummary(
        MockId id,
        long sizeBytes,
        Instant modifiedAt,
        boolean inherited,
        String inheritedFrom) {}
