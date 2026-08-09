package com.tao.sandbox.control.view;

import java.util.Map;

/**
 * A request to try without sending it.
 *
 * <p>Two shapes, because the protocols identify an operation differently. SOAP is pasted whole —
 * the envelope carries everything, and a person debugging one has it on their clipboard already.
 * REST is described, because its method and path carry meaning that no body contains.
 *
 * @param method present means REST; absent means the body is a SOAP envelope
 * @param scenarioId which scenario to resolve against, defaulting to the active one
 */
public record ResolveRequest(
        String scenarioId,
        String method,
        String path,
        Map<String, String> headers,
        String contentType,
        String body) {

    public boolean isRest() {
        return method != null && !method.isBlank();
    }
}
