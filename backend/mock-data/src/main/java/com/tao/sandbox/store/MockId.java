package com.tao.sandbox.store;

/**
 * Address of one stored mock.
 *
 * <p>The operation is part of the address, not just the service: operations within one service
 * return different shapes and cannot share a namespace.
 */
public record MockId(String scenarioId, String serviceId, String operationId, String fileName) {

    /**
     * Every part addresses exactly one directory or file, never a path.
     *
     * <p>Checked here rather than at the edge because the control panel accepts an id straight
     * from a URL and the filesystem store turns one into a path by resolution. {@code
     * baseline/petstore/showPetById/../../../../etc/passwd} is a well-formed four-part id and
     * would otherwise read whatever it named.
     */
    public MockId {
        requireSegment(scenarioId, "scenario");
        requireSegment(serviceId, "service");
        requireSegment(operationId, "operation");
        requireSegment(fileName, "file name");
    }

    private static void requireSegment(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mock id is missing its " + what);
        }
        if (value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(
                    "Illegal %s '%s': each part of a mock id names one directory or file".formatted(what, value));
        }
    }

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
