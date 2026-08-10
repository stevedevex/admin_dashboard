package com.tao.sandbox.control.view;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * What a client would actually have received.
 *
 * <p>The response verbatim, not a description of one. The distinction is the reason the endpoint
 * exists: a mock file holds a payload, and what leaves the server is that payload wrapped in an
 * envelope, given a status the sidecar or the contract chose, and carrying headers neither the file
 * nor the dashboard shows. Somebody checking whether the sandbox will satisfy their client needs the
 * bytes, not the ingredients.
 *
 * @param url what was called, so the loopback is visible rather than implied — a reader comparing
 *     this against their own client's configuration is the point
 * @param requestId the log entry this call was recorded under, or null if the response carried no
 *     id. The full trace is one call away at {@code /__tao/requests/{id}}, which is why nothing here
 *     duplicates it.
 * @param discarded the one part of the trace the log cannot carry: enumerating a request's field
 *     names means parsing its body, which the serving path deliberately never does. Computed here
 *     from the same declared keys, so the playground can answer it without the data plane paying
 *     for it on every request.
 * @param tookMillis the round trip as the dashboard experienced it, loopback overhead included.
 *     Deliberately not presented as the resolution time — the log entry holds that, measured where
 *     it means something.
 */
public record PlaygroundResult(
        String serviceId,
        String operationId,
        String scenarioId,
        String url,
        int status,
        SequencedMap<String, String> headers,
        String body,
        long tookMillis,
        String requestId,
        List<String> discarded) {}
