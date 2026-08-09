package com.tao.sandbox.store;

/**
 * A stored response: the payload, an optional protocol envelope header, whatever the sidecar chose
 * to override, and — when the mock was written from a recorded call — that call.
 *
 * <p>The store deliberately does not decide status or content type. It cannot — those come from
 * the contract, which only the protocol layer has read. Keeping the decision there is what lets a
 * Petstore {@code POST} answer 201 without anyone configuring it.
 *
 * @param envelopeHeader SOAP header content, or null when the mock has none. Named for the
 *     envelope to keep it clearly distinct from {@link MockMeta#headers()}, which are HTTP.
 * @param request the call this mock was written for, or null. Documentation, never a matcher:
 *     resolution reads declared keys and nothing else, so that a correlation id or a timestamp
 *     moving cannot change which mock answers. What this records is the question a reader has
 *     later — what a call that lands here actually looks like.
 */
public record MockDocument(String body, String envelopeHeader, MockMeta meta, String request) {

    public enum Kind {
        RESPONSE,
        FAULT
    }

    public MockDocument(String body, String envelopeHeader, MockMeta meta) {
        this(body, envelopeHeader, meta, null);
    }

    public static MockDocument of(String body) {
        return new MockDocument(body, null, MockMeta.none(), null);
    }
}
