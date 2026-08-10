package com.tao.sandbox.runtime.soap;

import com.tao.sandbox.observe.RequestLog;
import com.tao.sandbox.runtime.resolve.MockPipeline;
import com.tao.sandbox.runtime.resolve.OperationLocator;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockDocument.Kind;
import com.tao.sandbox.spec.wsdl.SoapOperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import com.tao.sandbox.xml.Xml;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.namespace.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.w3c.dom.Document;

/**
 * Client-facing SOAP endpoints.
 *
 * <p>One POST per service. No CXF and no Spring-WS: a JAX-WS client is satisfied by any server
 * that returns a well-formed envelope, and the frameworks buy nothing a mock needs.
 */
@Configuration
public class SoapRoutes {

    private static final Logger log = LoggerFactory.getLogger(SoapRoutes.class);

    /** {@code serviceId endpoint} → the WSDL rewritten for that endpoint. See {@link #wsdl}. */
    private final ConcurrentHashMap<String, String> rewrittenWsdls = new ConcurrentHashMap<>();

    /**
     * Named distinctly from the enclosing class on purpose: a {@code @Configuration} class is
     * itself a bean whose default name is the decapitalised class name, so a {@code @Bean} method
     * called {@code soapRoutes} inside {@code SoapRoutes} collides with it.
     */
    @Bean
    RouterFunction<ServerResponse> soapMockRoutes(
            SpecRegistry registry, MockPipeline pipeline, RequestLog requests) {
        RouterFunctions.Builder routes = RouterFunctions.route();

        for (SoapServiceDefinition service : registry.soapServices()) {
            routes.route(
                    RequestPredicates.GET(service.path()).and(request -> request.param("wsdl").isPresent()),
                    request -> wsdl(service, request));

            routes.route(
                    RequestPredicates.GET(service.path()).and(request -> request.param("xsd").isPresent()),
                    request -> importedSchema(service, request));

            routes.route(
                    RequestPredicates.POST(service.path()), request -> handle(service, request, pipeline, requests));

            log.info(
                    "Routing SOAP {} -> {} serving {}",
                    service.path(),
                    service.serviceId(),
                    service.served().keySet());
        }

        return routes.build();
    }

    private ServerResponse handle(
            SoapServiceDefinition service, ServerRequest request, MockPipeline pipeline, RequestLog requests) {

        RequestLog.Source source =
                RequestLog.Source.of(request.headers().firstHeader(RequestLog.SOURCE_HEADER));

        Document envelope;
        SoapVersion version;
        QName bodyElement;
        String raw;

        try {
            raw = request.body(String.class);
            envelope = Xml.parse(raw);
            version =
                    SoapVersion.of(envelope)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unrecognised envelope namespace: "
                                                            + envelope.getDocumentElement().getNamespaceURI()));
            bodyElement = SoapEnvelope.bodyElement(envelope, version);
        } catch (Exception e) {
            // The version is unknown at this point, so answer in 1.1 — the older client is the one
            // more likely to be unable to read the newer format.
            String reason = "Malformed SOAP request: " + e.getMessage();
            long logged = requests.recordRejected(service.serviceId(), reason, SoapVersion.SOAP_1_1.httpStatusFor(
                    SoapVersion.SOAP_1_1.senderCode()), null, source);
            return fault(SoapVersion.SOAP_1_1, SoapVersion.SOAP_1_1.senderCode(),
                    "Malformed SOAP request", String.valueOf(e.getMessage()), logged);
        }

        SoapOperationDefinition operation;
        switch (OperationLocator.forSoap(service, bodyElement)) {
            case OperationLocator.SoapMatch.Served served -> operation = served.operation();

            case OperationLocator.SoapMatch.Unknown ignored -> {
                String reason = "Unknown operation for body element " + bodyElement;
                long logged = requests.recordRejected(
                        service.serviceId(), reason, version.httpStatusFor(version.senderCode()), raw, source);
                return fault(version, version.senderCode(), reason,
                        "Known elements: " + service.elementToOperation().keySet(), logged);
            }

            // Present in the contract, absent from configuration. Saying so plainly is far more
            // useful than an empty response that looks like a data problem.
            case OperationLocator.SoapMatch.NotConfigured notConfigured -> {
                String name = notConfigured.operationName();
                long logged = requests.recordRejected(
                        service.serviceId(),
                        "NOT_IMPLEMENTED: '%s' is in the contract but not configured".formatted(name),
                        501,
                        raw,
                        source);
                return ServerResponse.status(501)
                        .header("Content-Type", version.contentType())
                        .header(RequestLog.REQUEST_ID_HEADER, String.valueOf(logged))
                        .body(
                                SoapEnvelope.fault(
                                        version,
                                        version.receiverCode(),
                                        "NOT_IMPLEMENTED: operation '%s' is not configured for mocking"
                                                .formatted(name),
                                        "Served operations: " + service.served().keySet()));
            }
        }

        var outcome =
                pipeline.resolve(
                        operation,
                        new SoapRequestFacade(
                                envelope, request.headers().asHttpHeaders(), service.namespaces(), version));

        if (outcome.document().isEmpty()) {
            long logged = requests.record(
                    outcome.trace(), version.httpStatusFor(version.receiverCode()), raw, null, source);
            return fault(version, version.receiverCode(),
                    "No mock matched this request", outcome.trace().explain(), logged);
        }

        var document = outcome.document().get();
        var meta = document.meta();
        boolean isFault = meta.kindOr(Kind.RESPONSE) == Kind.FAULT;

        // Per-mock header wins; otherwise the service-wide one; otherwise no header at all.
        String envelopeHeader =
                document.envelopeHeader() != null
                        ? document.envelopeHeader()
                        : service.defaultResponseHeader();

        String body =
                isFault
                        ? SoapEnvelope.fault(version, version.receiverCode(), "Mocked fault", document.body())
                        : SoapEnvelope.wrap(document.body(), version, envelopeHeader);

        int status = meta.statusOr(version.defaultStatusFor(meta.kindOr(Kind.RESPONSE)));

        // The wrapped envelope, not the stored payload: the log should show what left the server.
        long logged = requests.record(outcome.trace(), status, raw, body, source);

        var response =
                ServerResponse.status(status)
                        .header("Content-Type", meta.contentTypeOr(version.contentType()));
        meta.headers().forEach(response::header);

        // After the mock's own headers: the id is what the server did with this call, not something
        // a stored response gets to describe.
        response.header(RequestLog.REQUEST_ID_HEADER, String.valueOf(logged));
        return response.body(body);
    }

    private ServerResponse fault(
            SoapVersion version, String code, String message, String detail, long logged) {
        return ServerResponse.status(version.httpStatusFor(code))
                .header("Content-Type", version.contentType())
                .header(RequestLog.REQUEST_ID_HEADER, String.valueOf(logged))
                .body(SoapEnvelope.fault(version, code, message, detail));
    }

    /**
     * Serves the contract with {@code soap:address location} pointed at this server, and any
     * imported schema locations pointed at {@code ?xsd=}.
     *
     * <p>Without the rewrite a client that resolves its endpoint from the WSDL reads the real
     * service's address out of it and calls production — which presents as the sandbox being
     * ignored, and is genuinely hard to diagnose.
     */
    private ServerResponse wsdl(SoapServiceDefinition service, ServerRequest request) {
        String endpoint = endpoint(request, service);

        // The rewrite string-replaces the whole document, and JAX-WS clients fetch the WSDL far
        // more often than contracts change (which is never, without a restart). Keyed by endpoint
        // because that echoes the caller's Host header; capped so unbounded Host values cannot
        // grow the map — past the cap the odd caller just pays for its own rewrite.
        String served =
                rewrittenWsdls.size() >= 32
                        ? service.wsdlServedFrom(endpoint)
                        : rewrittenWsdls.computeIfAbsent(
                                service.serviceId() + " " + endpoint, key -> service.wsdlServedFrom(endpoint));

        return ServerResponse.ok().header("Content-Type", "text/xml;charset=UTF-8").body(served);
    }

    /** Imported schemas, so a client resolving the WSDL's imports stays inside the sandbox. */
    private ServerResponse importedSchema(SoapServiceDefinition service, ServerRequest request) {
        String name = request.param("xsd").orElse("");
        String content = service.imports().get(name);

        return content == null
                ? ServerResponse.notFound().build()
                : ServerResponse.ok().header("Content-Type", "text/xml;charset=UTF-8").body(content);
    }

    private String endpoint(ServerRequest request, SoapServiceDefinition service) {
        return request.uri().resolve(service.path()).toString().replaceAll("\\?.*$", "");
    }
}
