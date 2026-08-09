package com.tao.sandbox.control.view;

/**
 * A payload to check.
 *
 * <p>Takes a body rather than a mock id, so the editor validates what is on screen instead of what
 * was last saved. An author fixing a mock needs the verdict before deciding whether to keep it.
 */
public record ValidateRequest(String serviceId, String operationId, String body) {}
