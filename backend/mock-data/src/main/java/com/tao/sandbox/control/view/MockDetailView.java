package com.tao.sandbox.control.view;

import com.tao.sandbox.store.MockMeta;

/**
 * One mock, with its payload and both readings of what it will answer with.
 *
 * @param summary the same metadata the list carries, so opening a file is one call rather than a
 *     fetch plus a join against the listing — and so the size and state shown beside a payload
 *     cannot disagree with the tree that led to it
 * @param meta what the sidecar declares. Nulls mean "not specified".
 * @param effective what a client would actually receive, once the contract's defaults are applied
 */
public record MockDetailView(
        String id,
        MockSummaryView summary,
        String body,
        String envelopeHeader,
        MockMeta meta,
        Effective effective) {

    /**
     * Returned alongside {@code meta} rather than instead of it, because an author has to be able
     * to see the difference. A mock answering 201 with nothing in its sidecar got that from the
     * OpenAPI document; without both readings on screen the only way to discover that is to change
     * something and watch what moves.
     */
    public record Effective(int status, String contentType) {}
}
