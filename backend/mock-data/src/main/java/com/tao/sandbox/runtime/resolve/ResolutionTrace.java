package com.tao.sandbox.runtime.resolve;

import com.tao.sandbox.store.MockId;
import java.time.Duration;
import java.util.List;
import java.util.SequencedMap;

/**
 * What happened during one resolution, hit or miss.
 *
 * <p>Built once and used three ways: the request log, the dry-run resolve endpoint, and the
 * diagnostic returned on a miss. That reuse is the reason resolution lives in a single service
 * rather than being spread across the protocol handlers.
 */
public record ResolutionTrace(
        String serviceId,
        String operationId,
        String scenarioId,
        SequencedMap<String, String> extracted,
        List<String> attempted,
        MockId matched,
        boolean inherited,
        Duration took) {

    public boolean hit() {
        return matched != null;
    }

    /** Human-readable form, used verbatim in miss diagnostics. */
    public String explain() {
        StringBuilder text = new StringBuilder();
        text.append("service=").append(serviceId).append(" operation=").append(operationId);
        text.append(" scenario=").append(scenarioId).append(System.lineSeparator());
        text.append("extracted: ").append(extracted.isEmpty() ? "(none)" : extracted).append(System.lineSeparator());
        text.append("attempted:").append(System.lineSeparator());
        attempted.forEach(path -> text.append("  - ").append(path).append(System.lineSeparator()));
        text.append("matched: ").append(matched == null ? "(nothing)" : matched.asPath());
        return text.toString();
    }
}
