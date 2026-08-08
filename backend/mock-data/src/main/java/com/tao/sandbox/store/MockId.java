package com.tao.sandbox.store;

/**
 * Address of one stored mock.
 *
 * <p>The operation is part of the address, not just the service: operations within one service
 * return different shapes and cannot share a namespace.
 */
public record MockId(String scenarioId, String serviceId, String operationId, String fileName) {

    public String asPath() {
        return String.join("/", scenarioId, serviceId, operationId, fileName);
    }

    public static MockId parse(String path) {
        String[] parts = path.split("/", 4);
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "Expected scenario/service/operation/file, got: " + path);
        }
        return new MockId(parts[0], parts[1], parts[2], parts[3]);
    }
}
