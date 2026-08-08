package com.tao.sandbox.store;

/**
 * A stored response: the payload, an optional protocol envelope header, and whatever the sidecar
 * chose to override.
 *
 * <p>The store deliberately does not decide status or content type. It cannot — those come from
 * the contract, which only the protocol layer has read. Keeping the decision there is what lets a
 * Petstore {@code POST} answer 201 without anyone configuring it.
 *
 * @param envelopeHeader SOAP header content, or null when the mock has none. Named for the
 *     envelope to keep it clearly distinct from {@link MockMeta#headers()}, which are HTTP.
 */
public record MockDocument(String body, String envelopeHeader, MockMeta meta) {

    public enum Kind {
        RESPONSE,
        FAULT
    }

    public static MockDocument of(String body) {
        return new MockDocument(body, null, MockMeta.none());
    }
}
