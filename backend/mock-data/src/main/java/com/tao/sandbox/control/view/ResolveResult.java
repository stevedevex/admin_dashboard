package com.tao.sandbox.control.view;

import java.util.List;
import java.util.Map;

/**
 * Where a request would have resolved, and what was tried on the way.
 *
 * @param discarded fields the request carried that no declared key reads. The point of the whole
 *     endpoint: seeing a correlation id or a timestamp listed here is what turns "it did not match"
 *     into "of course, that is not what identifies it".
 * @param matched null is a successful answer describing a miss, not an error — the trace above it
 *     explains why, which is the entire reason to ask.
 */
public record ResolveResult(
        String serviceId,
        String operationId,
        String scenarioId,
        Map<String, String> extracted,
        List<String> discarded,
        List<String> attempted,
        String matched,
        boolean inherited,
        long tookMillis) {}
