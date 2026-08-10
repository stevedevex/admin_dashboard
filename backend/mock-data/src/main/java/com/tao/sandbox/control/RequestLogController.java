package com.tao.sandbox.control;

import com.tao.sandbox.control.view.MockDraft;
import com.tao.sandbox.observe.RequestLog;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.runtime.resolve.MockNaming;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
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
    private final SpecRegistry registry;
    private final MockRepository repository;
    private final ActiveScenario activeScenario;
    private final MockNaming naming;

    RequestLogController(
            RequestLog requests,
            SpecRegistry registry,
            MockRepository repository,
            ActiveScenario activeScenario,
            MockNaming naming) {
        this.requests = requests;
        this.registry = registry;
        this.repository = repository;
        this.activeScenario = activeScenario;
        this.naming = naming;
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
    RequestLog.Entry entry(@PathVariable String id) {
        return get(id);
    }

    /**
     * The mock this call is asking for: where it would live, what it would be called, and an empty
     * payload shaped like the declared response.
     *
     * <p>A read. Nothing is created — an author fills the payload in and saves it through {@code
     * PUT /__tao/mocks/{id}} like any other, so validation, freshness and the sidecars all behave
     * exactly as they do everywhere else. Creating the file here would add a well-formed empty
     * response to the library, which is precisely the upstream behaviour the sandbox exists to
     * eliminate.
     *
     * <p>The file name is derived by the same rules extraction and {@code /mocks/name} apply,
     * because a name computed any other way produces a file no request can ever resolve to.
     */
    @GetMapping("/{id}/draft")
    MockDraft draft(@PathVariable String id) {
        RequestLog.Entry entry = get(id);

        if (entry.serviceId() == null || entry.operationId() == null) {
            throw ControlPanelProblem.unprocessable(
                    "not-resolvable",
                    "Nothing to mock",
                    "This call was rejected before it reached an operation, so there is no contract to "
                            + "write a mock against. Its response says why.");
        }

        ServedOperation operation =
                registry
                        .findOperation(entry.serviceId(), entry.operationId())
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.unprocessable(
                                                "operation-not-served",
                                                "No longer served",
                                                "%s/%s answered this call but is not served now — configuration or the "
                                                        .formatted(entry.serviceId(), entry.operationId())
                                                        + "spec changed since."));

        // The keys are already normalised: they were extracted from the live request by the same
        // extractor resolution uses. Recomputing them from the body would be a second
        // implementation of the one rule that must never have two.
        SequencedMap<String, String> keys = new LinkedHashMap<>(entry.extracted());

        // A call whose keys did not satisfy the strategy resolved through the operation's default,
        // so that is the honest proposal. Naming a file from the partial keys would create one no
        // request can reach — the same trap /mocks/name refuses outright.
        String note = null;
        if (!MockNaming.satisfies(operation, keys) && !keys.isEmpty()) {
            note =
                    "This call carried %s, but %s resolves on all of %s. It was answered by the operation's default, so that is what this would write."
                            .formatted(
                                    keys.keySet(),
                                    entry.operationId(),
                                    operation.keys().stream().map(KeySpec::name).toList());
            keys = new LinkedHashMap<>();
        }

        String scenarioId = entry.scenarioId() == null ? activeScenario.get() : entry.scenarioId();
        String fileName = naming.fileNameFor(entry.serviceId(), entry.operationId(), keys);
        MockId mockId = new MockId(scenarioId, entry.serviceId(), entry.operationId(), fileName);

        return new MockDraft(
                mockId.asPath(),
                entry.serviceId(),
                entry.operationId(),
                scenarioId,
                fileName,
                keys,
                repository.get(mockId).isPresent(),
                skeletonFor(entry.serviceId(), entry.operationId()),
                entry.requestBody(),
                note);
    }

    /** The same skeleton {@code /services/…/schema} offers; one source, so the two cannot differ. */
    private String skeletonFor(String serviceId, String operationId) {
        if (registry.findRest(serviceId, operationId).isPresent()) {
            return Skeletons.fromJsonSchema(registry.findResponseSchema(serviceId, operationId).orElse(null));
        }
        return registry.soapSchemas(serviceId).flatMap(schemas -> schemas.skeleton(operationId)).orElse(null);
    }

    private RequestLog.Entry get(String id) {
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
