package com.tao.sandbox.control;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.control.view.ResolveRequest;
import com.tao.sandbox.control.view.ResolveResult;
import com.tao.sandbox.runtime.match.DescribedRequestFacade;
import com.tao.sandbox.runtime.match.RequestFacade;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.runtime.resolve.MockPipeline;
import com.tao.sandbox.runtime.resolve.OperationLocator;
import com.tao.sandbox.runtime.resolve.ResolutionTrace;
import com.tao.sandbox.runtime.soap.SoapEnvelope;
import com.tao.sandbox.runtime.soap.SoapRequestFacade;
import com.tao.sandbox.runtime.soap.SoapVersion;
import com.tao.sandbox.xml.Xml;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.SpecRegistry;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;

/**
 * The dry run: paste a request, find out what it would get.
 *
 * <p>Nothing is served and nothing is stored. It runs the real {@link MockPipeline} rather than a
 * reimplementation of the matching rules — a dry run with its own copy of them would agree with the
 * server right up until they drifted, which is exactly when someone would be relying on it.
 *
 * <p>The same applies to the step before matching, which this once did carry its own copy of:
 * identifying the operation goes through {@link OperationLocator}, so a request the dry run says
 * lands on an operation is one the router would have sent there. What remains here is only the
 * work of turning a described or pasted request into something either can read.
 */
@RestController
class ResolveController {

    private final SpecRegistry registry;
    private final MockPipeline pipeline;
    private final ActiveScenario activeScenario;
    private final SandboxProperties properties;

    ResolveController(
            SpecRegistry registry,
            MockPipeline pipeline,
            ActiveScenario activeScenario,
            SandboxProperties properties) {
        this.registry = registry;
        this.pipeline = pipeline;
        this.activeScenario = activeScenario;
        this.properties = properties;
    }

    @PostMapping(
            value = "/__tao/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResolveResult resolve(@RequestBody ResolveRequest request) {
        if (request == null) {
            throw ControlPanelProblem.badRequest("empty-request", "Empty request", "Describe a request to try");
        }

        String scenarioId =
                request.scenarioId() == null || request.scenarioId().isBlank()
                        ? activeScenario.get()
                        : request.scenarioId();

        Attempt attempt = request.isRest() ? rest(request, scenarioId) : soap(request, scenarioId);

        MockPipeline.Outcome outcome = pipeline.resolve(attempt.operation(), attempt.facade());
        ResolutionTrace trace = outcome.trace();

        return new ResolveResult(
                trace.serviceId(),
                trace.operationId(),
                trace.scenarioId(),
                new LinkedHashMap<>(trace.extracted()),
                discarded(attempt.facade(), trace),
                trace.attempted(),
                trace.matched() == null ? null : trace.matched().asPath(),
                trace.inherited(),
                trace.took().toMillis());
    }

    // --- identifying the operation -----------------------------------------

    private record Attempt(ServedOperation operation, RequestFacade facade) {}

    /**
     * REST is described rather than pasted, because its method and path carry meaning that no body
     * contains. The path is matched against the same patterns the router registered, so a path that
     * resolves here is one the router would have accepted.
     */
    private Attempt rest(ResolveRequest request, String scenarioId) {
        if (request.path() == null || request.path().isBlank()) {
            throw ControlPanelProblem.badRequest("missing-path", "Missing path", "A REST request needs a path");
        }

        URI uri = URI.create(request.path());
        PathContainer path = PathContainer.parsePath(uri.getRawPath() == null ? request.path() : uri.getRawPath());

        OperationLocator.RestMatch match =
                OperationLocator.forRest(registry.restOperations(), request.method(), path)
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.unprocessable(
                                                "no-such-route",
                                                "Nothing serves that",
                                                "%s %s matches no served operation. Served: %s"
                                                        .formatted(request.method(), request.path(), routes())));

        return new Attempt(
                match.operation(),
                new DescribedRequestFacade(
                        match.pathVariables(),
                        queryOf(uri),
                        headersWithScenario(request.headers(), scenarioId),
                        request.body()));
    }

    /**
     * SOAP is pasted whole. The envelope carries everything needed to identify the operation, and
     * whoever is debugging one already has it on their clipboard.
     */
    private Attempt soap(ResolveRequest request, String scenarioId) {
        if (request.body() == null || request.body().isBlank()) {
            throw ControlPanelProblem.badRequest(
                    "empty-body", "Empty body", "Paste a SOAP envelope, or send a method and path for REST");
        }

        Document envelope;
        try {
            envelope = Xml.parse(request.body());
        } catch (RuntimeException e) {
            throw ControlPanelProblem.unprocessable(
                    "malformed-envelope", "Not a SOAP envelope", String.valueOf(e.getMessage()));
        }

        SoapVersion version =
                SoapVersion.of(envelope)
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.unprocessable(
                                                "malformed-envelope",
                                                "Not a SOAP envelope",
                                                "Unrecognised envelope namespace: "
                                                        + envelope.getDocumentElement().getNamespaceURI()));

        QName bodyElement;
        try {
            bodyElement = SoapEnvelope.bodyElement(envelope, version);
        } catch (RuntimeException e) {
            throw ControlPanelProblem.unprocessable(
                    "malformed-envelope", "Not a SOAP envelope", String.valueOf(e.getMessage()));
        }

        return switch (OperationLocator.forSoap(registry.soapServices(), bodyElement)) {
            case OperationLocator.SoapMatch.Served served -> {
                HttpHeaders headers = new HttpHeaders();
                headersWithScenario(request.headers(), scenarioId).forEach(headers::add);

                yield new Attempt(
                        served.operation(),
                        new SoapRequestFacade(
                                envelope, headers, served.service().namespaces(), version));
            }

            // In the contract, absent from configuration — the same NOT_IMPLEMENTED the live
            // endpoint answers, said here before anyone sends it for real.
            case OperationLocator.SoapMatch.NotConfigured notConfigured ->
                    throw ControlPanelProblem.unprocessable(
                            "operation-not-served",
                            "Not configured for mocking",
                            "'%s' is in %s's contract but is not configured. Served: %s"
                                    .formatted(
                                            notConfigured.operationName(),
                                            notConfigured.service().serviceId(),
                                            notConfigured.service().served().keySet()));

            case OperationLocator.SoapMatch.Unknown ignored ->
                    throw ControlPanelProblem.unprocessable(
                            "no-such-operation",
                            "Nothing serves that",
                            "No service maps the body element %s. Known elements: %s"
                                    .formatted(bodyElement, soapElements()));
        };
    }

    // --- internals ---------------------------------------------------------

    /**
     * The scenario travels as the override header, which is how a real request asks for one. That
     * keeps the dry run using the pipeline's own precedence rather than a second way in.
     */
    private Map<String, String> headersWithScenario(Map<String, String> supplied, String scenarioId) {
        Map<String, String> headers = supplied == null ? new LinkedHashMap<>() : new LinkedHashMap<>(supplied);
        headers.put(properties.scenario().header(), scenarioId);
        return headers;
    }

    /** What the request carried that no declared key reads. */
    private List<String> discarded(RequestFacade facade, ResolutionTrace trace) {
        List<String> discarded = new ArrayList<>();
        for (String field : facade.fieldNames()) {
            if (!trace.extracted().containsKey(field) && !discarded.contains(field)) {
                discarded.add(field);
            }
        }
        return discarded;
    }

    private Map<String, String> queryOf(URI uri) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return parameters;
        }

        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                parameters.put(
                        URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }

    private List<String> routes() {
        return registry.restOperations().stream()
                .map(operation -> operation.method() + " " + operation.path())
                .toList();
    }

    private List<QName> soapElements() {
        return registry.soapServices().stream()
                .flatMap(service -> service.elementToOperation().keySet().stream())
                .toList();
    }
}
