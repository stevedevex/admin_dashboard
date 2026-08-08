package com.tao.sandbox.store;

import java.util.Map;

/**
 * Optional overrides stored beside a payload in {@code <name>.meta.yaml}.
 *
 * <p>Every field is nullable and means "not specified". The contract is a precedence chain:
 *
 * <pre>
 *   sidecar  &gt;  the contract's own declaration  &gt;  a protocol default
 * </pre>
 *
 * <p>So a mock inherits 201 Created from the OpenAPI document without configuration, and only
 * needs a sidecar when it wants something the contract does not declare — a 500, a {@code
 * Location} header, a deliberate fault.
 */
public record MockMeta(
        Integer status, String contentType, Map<String, String> headers, MockDocument.Kind kind) {

    private static final MockMeta NONE = new MockMeta(null, null, Map.of(), null);

    public static MockMeta none() {
        return NONE;
    }

    public MockMeta {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public int statusOr(int fallback) {
        return status != null ? status : fallback;
    }

    public String contentTypeOr(String fallback) {
        return contentType != null ? contentType : fallback;
    }

    public MockDocument.Kind kindOr(MockDocument.Kind fallback) {
        return kind != null ? kind : fallback;
    }
}
