package com.tao.sandbox.control;

import com.tao.sandbox.control.view.MockDetailView;
import com.tao.sandbox.control.view.MockName;
import com.tao.sandbox.control.view.MockNameRequest;
import com.tao.sandbox.control.view.MockSaveRequest;
import com.tao.sandbox.control.view.MockSummaryView;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.runtime.resolve.MockNaming;
import com.tao.sandbox.runtime.soap.SoapVersion;
import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.ServiceDescriptor;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockMeta;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.MockStem;
import com.tao.sandbox.store.MockSummary;
import com.tao.sandbox.store.Payloads;
import com.tao.sandbox.store.Scenario;
import com.tao.sandbox.validate.MockReachability;
import com.tao.sandbox.validate.MockStates;
import com.tao.sandbox.validate.MockValidator;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Browsing the mock library, and computing the name a new mock must be saved under. */
@RestController
@RequestMapping(value = "/__tao/mocks", produces = MediaType.APPLICATION_JSON_VALUE)
class MockCatalogController {

    private final MockRepository repository;
    private final SpecRegistry registry;
    private final ActiveScenario activeScenario;
    private final MockNaming naming;
    private final MockStates states;
    private final MockValidator validator;
    private final MockReachability reachability;

    MockCatalogController(
            MockRepository repository,
            SpecRegistry registry,
            ActiveScenario activeScenario,
            MockNaming naming,
            MockStates states,
            MockValidator validator,
            MockReachability reachability) {
        this.repository = repository;
        this.registry = registry;
        this.activeScenario = activeScenario;
        this.naming = naming;
        this.states = states;
        this.validator = validator;
        this.reachability = reachability;
    }

    /**
     * Metadata only. Bodies are never returned here — a library of multi-megabyte payloads would
     * make browsing unusable.
     *
     * <p>Results include what the scenario inherits, flagged. The chain is resolved server-side
     * because only the server knows it.
     */
    @GetMapping
    List<MockSummaryView> list(
            @RequestParam(required = false) String scenario, @RequestParam(required = false) String service) {

        String scenarioId = (scenario == null || scenario.isBlank()) ? activeScenario.get() : scenario;
        requireScenario(scenarioId);
        requireService(service);

        return repository.list(scenarioId, service).stream().map(this::describe).toList();
    }

    /**
     * Metadata plus the payload and anything the sidecars supply.
     *
     * <p>Matched as the rest of the path rather than as one encoded segment: a mock id contains
     * slashes, and a servlet container rejects {@code %2F} in a path by default. So the id travels
     * as what it already is — a path.
     */
    @GetMapping("/{*id}")
    ResponseEntity<MockDetailView> get(@PathVariable String id) {
        MockId mockId = parse(id);

        MockDocument document =
                repository
                        .get(mockId)
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.notFound(
                                                "mock-not-found",
                                                "No such mock",
                                                mockId.asPath() + " does not exist"));

        // An ETag over the content, not the timestamp: a mounted share's clock is not this
        // machine's, and a second-resolution mtime cannot tell two edits inside one second apart.
        return ResponseEntity.ok().eTag(MockEtags.etag(document)).body(detail(mockId, document));
    }

    /**
     * Creates or replaces a mock. The id carries everything needed to place the file, so a separate
     * create endpoint would only add a way for the two to disagree.
     *
     * <p>{@code If-Match} is required whenever the mock already exists. Without it, two dashboard
     * tabs against one mounted share overwrite each other silently, and the author who lost their
     * edit has no way to discover that they did.
     *
     * <p>On a mock that does not exist yet there is no version to match, so the header is optional
     * — but sending one is not ignored: it means the caller believed the mock existed, and that
     * belief is now wrong, which is exactly the stale-state case the header is for.
     */
    @PutMapping(value = "/{*id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MockDetailView> save(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody MockSaveRequest request) {

        MockId mockId = parse(id);

        if (request == null || request.body() == null) {
            throw ControlPanelProblem.badRequest(
                    "missing-body", "Missing body", "A mock needs a payload. Send an empty string for an empty one.");
        }

        // Checked on the way in, so the failure names the limit rather than arriving later as an
        // IOException from the filesystem — or, worse, succeeding here and failing on a colleague's
        // checkout, where the path around the name is longer.
        MockStem.problemWithLength(mockId.fileName())
                .ifPresent(
                        problem -> {
                            throw ControlPanelProblem.unprocessable(
                                    "name-too-long", "That name will not fit", problem);
                        });

        Optional<MockDocument> existing = repository.get(mockId);
        MockEtags.requireFreshness(mockId, existing.orElse(null), ifMatch);

        MockDocument document =
                new MockDocument(
                        request.body(),
                        request.envelopeHeader(),
                        request.meta() == null ? MockMeta.none() : request.meta(),
                        // Absent means "leave whatever is recorded", not "clear it": an ordinary
                        // save carries no request, and only the first save from a recorded call
                        // ever has one to offer.
                        request.request() != null
                                ? request.request()
                                : existing.map(MockDocument::request).orElse(null));

        try {
            repository.save(mockId, document);
        } catch (IllegalArgumentException e) {
            throw ControlPanelProblem.unprocessable("unknown-scenario", "Cannot save there", e.getMessage());
        }

        MockDocument saved = repository.get(mockId).orElseThrow();

        // Checked here rather than merely forgotten. The bytes and the schema are both in hand, so
        // the verdict costs one validation and spares the author the ritual of pressing Validate
        // on something they have this second written. It is also what keeps `unchecked` meaning
        // "nothing has looked at this" rather than "this is the most recent thing anybody saved".
        recordVerdict(mockId, saved);
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag(MockEtags.etag(saved))
                .body(detail(mockId, saved));
    }

    /** Removes the payload and its sidecars. A stale sidecar would re-apply to whatever is saved next. */
    @DeleteMapping("/{*id}")
    ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        MockId mockId = parse(id);

        MockDocument existing =
                repository
                        .get(mockId)
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.notFound(
                                                "mock-not-found", "No such mock", mockId.asPath() + " does not exist"));

        MockEtags.requireFreshness(mockId, existing, ifMatch);

        repository.delete(mockId);
        states.invalidate(mockId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Check a payload that has just been stored, and remember what was found.
     *
     * <p>Never allowed to fail the write. The payload is saved either way — this sandbox serves
     * what it is given, and refusing a mock because it does not satisfy a schema would make it
     * impossible to record the very responses somebody is trying to reproduce. A verdict that
     * could not be reached is simply absent, which is exactly what `unchecked` says.
     */
    private void recordVerdict(MockId mockId, MockDocument saved) {
        try {
            states.record(
                    mockId,
                    validator.validate(
                            mockId.serviceId(), mockId.operationId(), saved.body(), saved.meta()));
        } catch (RuntimeException e) {
            states.invalidate(mockId);
        }
    }

    /**
     * Computes a file name from key values. The dashboard must use this rather than building names
     * itself.
     *
     * <p>Normalisation — lowercasing, trimming, zero-stripping — decides whether a saved mock is
     * ever reachable. A second implementation in the dashboard would drift, and the failure is
     * silent: a file that exists and no request can resolve to.
     */
    @PostMapping(value = "/name", consumes = MediaType.APPLICATION_JSON_VALUE)
    MockName name(@RequestBody MockNameRequest request) {
        ServedOperation operation =
                registry
                        .findOperation(request.serviceId(), request.operationId())
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.notFound(
                                                "operation-not-found",
                                                "No such operation",
                                                "%s/%s is not served"
                                                        .formatted(request.serviceId(), request.operationId())));

        SequencedMap<String, String> resolved = MockNaming.resolveKeys(operation, request.keys());

        // Some keys but not all, under a strategy that requires all: the file would be created and
        // never reached, because no request satisfying fewer keys ever produces this signature.
        if (!resolved.isEmpty() && !MockNaming.satisfies(operation, resolved)) {
            throw ControlPanelProblem.unprocessable(
                    "incomplete-keys",
                    "Missing key values",
                    "%s/%s resolves on all of %s; got %s. A file named from a subset can never be reached — "
                            .formatted(
                                    request.serviceId(),
                                    request.operationId(),
                                    operation.keys().stream().map(KeySpec::name).toList(),
                                    resolved.keySet())
                            + "supply every key, or none for the operation's default mock.");
        }

        // Refused rather than escaped. Escaping would be the thorough fix and would rename every
        // file already on disk; a value carrying a separator is rare, and saying so plainly costs
        // an author one rename while silently mis-parsing costs them an afternoon.
        MockStem.problemWith(resolved)
                .ifPresent(
                        problem -> {
                            throw ControlPanelProblem.unprocessable(
                                    "unusable-key-value", "Cannot name a mock from that", problem);
                        });

        String fileName = naming.fileNameFor(operation.serviceId(), operation.operationId(), resolved);

        MockStem.problemWithLength(fileName)
                .ifPresent(
                        problem -> {
                            throw ControlPanelProblem.unprocessable(
                                    "name-too-long", "That name will not fit", problem);
                        });

        return new MockName(fileName, MockNaming.asWritten(resolved));
    }

    // --- internals ---------------------------------------------------------


    private MockId parse(String captured) {
        String path = captured.startsWith("/") ? captured.substring(1) : captured;

        if (path.isBlank()) {
            throw ControlPanelProblem.badRequest(
                    "malformed-request",
                    "Malformed request",
                    "Expected a mock id of the form scenario/service/operation/file");
        }

        // MockId validates its own parts, and ControlPanelErrors renders what it says.
        return MockId.parse(path);
    }

    private MockSummaryView describe(MockSummary summary) {
        MockId id = summary.id();
        MockStates.Assessment assessment = states.get(id);
        MockReachability.Verdict reach = reachability.of(id);

        return new MockSummaryView(
                id.asPath(),
                id.scenarioId(),
                id.serviceId(),
                id.operationId(),
                id.fileName(),
                Payloads.formatOf(id.fileName()),
                summary.sizeBytes(),
                summary.modifiedAt(),
                summary.inherited(),
                summary.inheritedFrom(),
                // Whatever validation has learned, which for a mock nobody has checked is that
                // nothing is known. The list never validates on the reader's behalf — see MockStates.
                assessment.state().wireName(),
                assessment.completeness(),
                reach.reachable(),
                reach.reason());
    }

    private MockDetailView detail(MockId id, MockDocument document) {
        return new MockDetailView(
                id.asPath(),
                summarise(id),
                document.body(),
                document.envelopeHeader(),
                document.request(),
                document.meta(),
                effective(id, document));
    }

    /**
     * The listing's metadata for one mock, found by asking the store for its scenario's list.
     *
     * <p>Taken from the same call the tree renders from, so the two cannot disagree. Null only for
     * a mock that exists on disk but is not yet indexed — an author's file dropped in since the
     * last reload, which {@link MockRepository#get} still serves so the editor can show it.
     */
    private MockSummaryView summarise(MockId id) {
        return repository.list(id.scenarioId(), id.serviceId()).stream()
                .filter(mock -> mock.id().equals(id))
                .findFirst()
                .map(this::describe)
                .orElse(null);
    }

    /**
     * What a client would actually receive: the sidecar, then what the contract declares, then a
     * protocol default — the same precedence the request handlers apply, because a preview that
     * computed it differently would be worse than not showing one.
     */
    private MockDetailView.Effective effective(MockId id, MockDocument document) {
        MockMeta meta = document.meta();

        Optional<OperationDefinition> rest = registry.findRest(id.serviceId(), id.operationId());
        if (rest.isPresent()) {
            return new MockDetailView.Effective(
                    meta.statusOr(rest.get().successStatus()), meta.contentTypeOr(rest.get().responseContentType()));
        }

        if (registry.findOperation(id.serviceId(), id.operationId()).isPresent()) {
            // SOAP. The version is echoed from the request and there is no request here, so 1.1 —
            // the same assumption the fault path makes when it cannot read one.
            SoapVersion version = SoapVersion.SOAP_1_1;

            return new MockDetailView.Effective(
                    meta.statusOr(version.defaultStatusFor(meta.kindOr(MockDocument.Kind.RESPONSE))),
                    meta.contentTypeOr(version.contentType()));
        }

        // A mock for an operation no longer served — a spec changed, or configuration dropped it.
        // The sidecar is all there is to go on, and saying so is more useful than inventing a
        // contract this file no longer has.
        return new MockDetailView.Effective(meta.statusOr(200), meta.contentType());
    }

    /**
     * An unknown scenario or service would otherwise list as empty, and an empty library reads as
     * "nothing here yet" rather than "you asked for something that does not exist".
     */
    private void requireScenario(String scenarioId) {
        if (repository.scenarios().stream().noneMatch(scenario -> scenario.id().equals(scenarioId))) {
            throw ControlPanelProblem.notFound(
                    "scenario-not-found",
                    "No such scenario",
                    "'%s' is not one of %s"
                            .formatted(scenarioId, repository.scenarios().stream().map(Scenario::id).toList()));
        }
    }

    private void requireService(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return;
        }
        if (registry.findService(serviceId).isEmpty()) {
            throw ControlPanelProblem.notFound(
                    "service-not-found",
                    "No such service",
                    "'%s' is not one of %s"
                            .formatted(serviceId, registry.services().stream().map(ServiceDescriptor::id).toList()));
        }
    }
}
