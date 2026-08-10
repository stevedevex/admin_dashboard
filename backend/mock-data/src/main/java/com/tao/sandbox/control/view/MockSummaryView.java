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
 * @param reachable whether any request could produce this file's name. False is not a broken
 *     payload — it is a file at an address nothing computes, so it never wins and the operation's
 *     default answers in its place. Silent everywhere else, hence reported here.
 * @param unreachableReason why, in words the author can act on; null when reachable
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
        Integer completeness,
        boolean reachable,
        String unreachableReason) {}
