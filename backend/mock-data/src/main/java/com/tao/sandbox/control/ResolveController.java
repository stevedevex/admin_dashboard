package com.tao.sandbox.control;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.control.view.ResolveRequest;
import com.tao.sandbox.control.view.ResolveResult;
import com.tao.sandbox.runtime.match.DescribedRequestFacade;
import com.tao.sandbox.runtime.match.RequestFacade;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.runtime.resolve.MockPipeline;
import com.tao.sandbox.runtime.resolve.ResolutionTrace;
import com.tao.sandbox.runtime.soap.SoapEnvelope;
import com.tao.sandbox.runtime.soap.SoapRequestFacade;
import com.tao.sandbox.runtime.soap.SoapVersion;
import com.tao.sandbox.runtime.soap.Xml;
import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.QName;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.w3c.dom.Document;

/**
 * The dry run: paste a request, find out what it would get.
 *
 * <p>Nothing is served and nothing is stored. It runs the real {@link MockPipeline} rather than a
 * reimplementation of the matching rules — a dry run with its own copy of them would agree with the
 * server right up until they drifted, which is exactly when someone would be relying on it.
 */
@RestController
class ResolveController {

    private static final PathPatternParser PATHS = PathPatternParser.defaultInstance;

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

        for (OperationDefinition operation : registry.restOperations()) {
            if (!operation.method().name().equalsIgnoreCase(request.method())) {
                continue;
            }

            PathPattern pattern = PATHS.parse(operation.path());
            PathPattern.PathMatchInfo match = pattern.matchAndExtract(path);
            if (match == null) {
                continue;
            }

            return new Attempt(
                    operation,
                    new DescribedRequestFacade(
                            match.getUriVariables(),
                            queryOf(uri),
                            headersWithScenario(request.headers(), scenarioId),
                            request.body()));
        }

        throw ControlPanelProblem.unprocessable(
                "no-such-route",
                "Nothing serves that",
                "%s %s matches no served operation. Served: %s"
                        .formatted(request.method(), request.path(), routes()));
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

        for (SoapServiceDefinition service : registry.soapServices()) {
            String operationName = service.elementToOperation().get(bodyElement);
            if (operationName == null) {
                continue;
            }

            Optional<ServedOperation> operation =
                    registry.findOperation(service.serviceId(), operationName);
            if (operation.isEmpty()) {
                // In the contract, absent from configuration — the same NOT_IMPLEMENTED the live
                // endpoint answers, said here before anyone sends it for real.
                throw ControlPanelProblem.unprocessable(
                        "operation-not-served",
                        "Not configured for mocking",
                        "'%s' is in %s's contract but is not configured. Served: %s"
                                .formatted(operationName, service.serviceId(), service.served().keySet()));
            }

            HttpHeaders headers = new HttpHeaders();
            headersWithScenario(request.headers(), scenarioId).forEach(headers::add);

            return new Attempt(
                    operation.get(),
                    new SoapRequestFacade(envelope, headers, service.namespaces(), version));
        }

        throw ControlPanelProblem.unprocessable(
                "no-such-operation",
                "Nothing serves that",
                "No service maps the body element %s. Known elements: %s".formatted(bodyElement, soapElements()));
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
                        java.net.URLDecoder.decode(pair.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8));
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
