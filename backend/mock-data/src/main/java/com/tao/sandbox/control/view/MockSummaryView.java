package com.tao.sandbox.control.view;

import java.time.Instant;

/**
 * Metadata for browsing. The body is never here — a library of multi-megabyte payloads would make
 * the tree unusable.
 *
 * @param id the path form {@code scenario/service/operation/file}, which is also the address for
 *     {@code GET|PUT|DELETE /__tao/mocks/{id}}
 * @param scenarioId the scenario the file actually lives in, which for an inherited mock is an
 *     ancestor of the one that was asked for
 * @param state one of {@code valid}, {@code incomplete}, {@code invalid}, {@code unchecked}
 * @param completeness percentage of schema-declared fields populated, or null when unknown
 */
public record MockSummaryView(
        String id,
        String scenarioId,
        String serviceId,
        String operationId,
        String fileName,
        String format,
        long sizeBytes,
        Instant modifiedAt,
        boolean inherited,
        String inheritedFrom,
        String state,
        Integer completeness) {}
