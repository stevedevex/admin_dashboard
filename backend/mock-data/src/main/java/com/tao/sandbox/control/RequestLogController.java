package com.tao.sandbox.control;

import com.tao.sandbox.observe.RequestLog;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the application under test actually called. */
@RestController
@RequestMapping(value = "/__tao/requests", produces = MediaType.APPLICATION_JSON_VALUE)
class RequestLogController {

    private static final int DEFAULT_LIMIT = 100;

    private final RequestLog requests;

    RequestLogController(RequestLog requests) {
        this.requests = requests;
    }

    /**
     * Polled with {@code ?since=}. No SSE: two seconds of staleness is invisible in a development
     * tool, and polling survives every corporate proxy that would quietly break a stream.
     *
     * <p>Bodies are excluded, as they are from the mock list and for the same reason. They are one
     * call away, on the entry the reader actually opens.
     */
    @GetMapping
    Page list(
            @RequestParam(required = false) String since,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {

        RequestLog.Page page;
        try {
            page = requests.since(since, limit);
        } catch (IllegalArgumentException e) {
            throw ControlPanelProblem.badRequest("bad-cursor", "Unusable cursor", e.getMessage());
        }

        return new Page(page.mode(), page.cursor(), page.entries().stream().map(Summary::of).toList());
    }

    @GetMapping("/{id}")
    RequestLog.Entry get(@PathVariable String id) {
        long entryId;
        try {
            entryId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw ControlPanelProblem.badRequest("bad-entry-id", "Unusable id", "Entry ids are numbers, got: " + id);
        }

        return requests
                .find(entryId)
                .orElseThrow(
                        () ->
                                ControlPanelProblem.notFound(
                                        "request-not-found",
                                        "No such log entry",
                                        "Entry %s is not retained — the log is bounded and it has aged out."
                                                .formatted(id)));
    }

    /**
     * @param mode {@code SAMPLED} when the buffer wrapped past the caller's cursor. It has to be
     *     said out loud: a silently truncating log is worse than none, because quiet reads as "no
     *     traffic".
     */
    record Page(String mode, String cursor, List<Summary> entries) {}

    /** One entry without its bodies. */
    record Summary(
            String id,
            Instant at,
            String serviceId,
            String operationId,
            String scenarioId,
            int status,
            long tookMillis,
            String matched,
            boolean inherited) {

        static Summary of(RequestLog.Entry entry) {
            return new Summary(
                    String.valueOf(entry.id()),
                    entry.at(),
                    entry.serviceId(),
                    entry.operationId(),
                    entry.scenarioId(),
                    entry.status(),
                    entry.tookMillis(),
                    entry.matched(),
                    entry.inherited());
        }
    }
}
