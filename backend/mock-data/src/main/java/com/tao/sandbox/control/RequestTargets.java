package com.tao.sandbox.control;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.control.view.ResolveRequest;
import com.tao.sandbox.runtime.match.DescribedRequestFacade;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.runtime.match.RequestFacade;
import com.tao.sandbox.runtime.resolve.OperationLocator;
import com.tao.sandbox.runtime.soap.SoapEnvelope;
import com.tao.sandbox.runtime.soap.SoapRequestFacade;
import com.tao.sandbox.runtime.soap.SoapVersion;
import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.spec.wsdl.SoapOperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import com.tao.sandbox.xml.Xml;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

/**
 * What a described or pasted request is aimed at.
 *
 * <p>Two endpoints ask this question for different reasons. The dry run needs the operation and a
 * readable request so it can resolve without serving; the playground needs the operation and a URL
 * so it can serve for real. Neither needs the other's half, but both need the same answer to "what
 * is this request, and does anything serve it" — including the same refusals, in the same words.
 *
 * <p>Kept in one place because the alternative was already tried elsewhere and documented as a
 * mistake: see {@link OperationLocator}, extracted after the question "which operation is this for"
 * had been answered three times. This class is the layer above it — turning a request as the
 * dashboard describes it into something the locator can decide on, and turning the locator's
 * verdict into a problem the dashboard can render.
 */
@Component
class RequestTargets {

    private final SpecRegistry registry;
    private final SandboxProperties properties;

    RequestTargets(SpecRegistry registry, SandboxProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * An identified request: what serves it, and what a key could read from it.
     *
     * <p>The protocol-specific parts stay visible rather than being narrowed away, because the
     * callers need different ones — the playground needs a SOAP service's endpoint path and content
     * type to post an envelope, which resolution has no use for.
     */
    sealed interface Target {

        ServedOperation operation();

        /** The request as key extraction reads it. */
        RequestFacade facade();

        record Rest(OperationDefinition operation, RequestFacade facade, URI uri) implements Target {}

        record Soap(
                SoapServiceDefinition service,
                SoapOperationDefinition operation,
                RequestFacade facade,
                SoapVersion version,
                Document envelope)
                implements Target {}
    }

    /**
     * @param scenarioId travels as the override header, which is how a real request asks for one.
     *     That keeps both callers using the pipeline's own precedence rather than a second way in.
     */
    Target locate(ResolveRequest request, String scenarioId) {
        if (request == null) {
            throw ControlPanelProblem.badRequest("empty-request", "Empty request", "Describe a request to try");
        }
        return request.isRest() ? rest(request, scenarioId) : soap(request, scenarioId);
    }

    /**
     * What the request carried that no declared key reads — the correlation ids, timestamps and
     * optional parameters extraction deliberately ignores. Seeing them listed is what turns "the
     * mock did not match" into "of course, the key is not what I thought".
     *
     * <p>Compared against what the operation <em>declares</em>, not against the names of the values
     * it extracted. Those are the same thing only until a key is given an alias: read {@code
     * productPriceInMinorUnits} and call it {@code price}, and comparing by extracted name reports
     * the field as ignored when it is the one that decided the answer. Saying a field was discarded
     * when it was read is worse than saying nothing — this list is consulted precisely by someone
     * who already believes the wrong thing about which fields matter.
     *
     * <p>Which is why the question is {@link KeySpec#reads} rather than {@code matchesName}. An alias
     * is one way a field and a key can be the same thing under different names; depth is another,
     * and the more common one. A key reaching {@code $.customer.id} reads {@code customer} as
     * surely as it reads {@code id}, and a comparison by name alone called the container ignored.
     */
    List<String> discarded(Target target) {
        List<String> discarded = new ArrayList<>();
        for (String field : target.facade().fieldNames()) {
            boolean read = target.operation().keys().stream().anyMatch(key -> key.reads(field));
            if (!read && !discarded.contains(field)) {
                discarded.add(field);
            }
        }
        return discarded;
    }

    // --- identifying the operation -----------------------------------------

    /**
     * REST is described rather than pasted, because its method and path carry meaning that no body
     * contains. The path is matched against the same patterns the router registered, so a path that
     * resolves here is one the router would have accepted.
     */
    private Target rest(ResolveRequest request, String scenarioId) {
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

        return new Target.Rest(
                match.operation(),
                new DescribedRequestFacade(
                        match.pathVariables(),
                        queryOf(uri),
                        headersWithScenario(request.headers(), scenarioId),
                        request.body()),
                uri);
    }

    /**
     * SOAP is pasted whole. The envelope carries everything needed to identify the operation, and
     * whoever is debugging one already has it on their clipboard.
     */
    private Target soap(ResolveRequest request, String scenarioId) {
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

                yield new Target.Soap(
                        served.service(),
                        served.operation(),
                        new SoapRequestFacade(envelope, headers, served.service().namespaces(), version),
                        version,
                        envelope);
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

    private Map<String, String> headersWithScenario(Map<String, String> supplied, String scenarioId) {
        Map<String, String> headers = supplied == null ? new LinkedHashMap<>() : new LinkedHashMap<>(supplied);
        headers.put(properties.scenario().header(), scenarioId);
        return headers;
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
