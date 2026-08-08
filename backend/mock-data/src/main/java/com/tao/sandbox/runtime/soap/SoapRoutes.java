package com.tao.sandbox.runtime.soap;

import com.tao.sandbox.runtime.resolve.MockPipeline;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockDocument.Kind;
import com.tao.sandbox.spec.wsdl.SoapOperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
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

    /**
     * Named distinctly from the enclosing class on purpose: a {@code @Configuration} class is
     * itself a bean whose default name is the decapitalised class name, so a {@code @Bean} method
     * called {@code soapRoutes} inside {@code SoapRoutes} collides with it.
     */
    @Bean
    RouterFunction<ServerResponse> soapMockRoutes(SpecRegistry registry, MockPipeline pipeline) {
        RouterFunctions.Builder routes = RouterFunctions.route();

        for (SoapServiceDefinition service : registry.soapServices()) {
            routes.route(
                    RequestPredicates.GET(service.path()).and(request -> request.param("wsdl").isPresent()),
                    request -> wsdl(service, request));

            routes.route(
                    RequestPredicates.GET(service.path()).and(request -> request.param("xsd").isPresent()),
                    request -> importedSchema(service, request));

            routes.route(RequestPredicates.POST(service.path()), request -> handle(service, request, pipeline));

            log.info(
                    "Routing SOAP {} -> {} serving {}",
                    service.path(),
                    service.serviceId(),
                    service.served().keySet());
        }

        return routes.build();
    }

    private ServerResponse handle(
            SoapServiceDefinition service, ServerRequest request, MockPipeline pipeline) {

        Document envelope;
        SoapVersion version;
        QName bodyElement;

        try {
            envelope = Xml.parse(request.body(String.class));
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
            return fault(SoapVersion.SOAP_1_1, SoapVersion.SOAP_1_1.senderCode(),
                    "Malformed SOAP request", String.valueOf(e.getMessage()));
        }

        String operationName = service.elementToOperation().get(bodyElement);
        if (operationName == null) {
            return fault(version, version.senderCode(),
                    "Unknown operation for body element " + bodyElement,
                    "Known elements: " + service.elementToOperation().keySet());
        }

        SoapOperationDefinition operation = service.served().get(operationName);
        if (operation == null) {
            // Present in the contract, absent from configuration. Saying so plainly is far more
            // useful than an empty response that looks like a data problem.
            return ServerResponse.status(501)
                    .header("Content-Type", version.contentType())
                    .body(
                            SoapEnvelope.fault(
                                    version,
                                    version.receiverCode(),
                                    "NOT_IMPLEMENTED: operation '%s' is not configured for mocking"
                                            .formatted(operationName),
                                    "Served operations: " + service.served().keySet()));
        }

        var outcome =
                pipeline.resolve(
                        operation,
                        new SoapRequestFacade(
                                envelope, request.headers().asHttpHeaders(), service.namespaces(), version));

        if (outcome.document().isEmpty()) {
            return fault(version, version.receiverCode(),
                    "No mock matched this request", outcome.trace().explain());
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

        // A fault carried over HTTP 200 is not a fault to most clients and proxies — they key off
        // the status long before they parse the body. So a fault defaults to the version's fault
        // status, and only an explicit sidecar entry can override that.
        int defaultStatus = isFault ? version.httpStatusFor(version.receiverCode()) : 200;

        var response =
                ServerResponse.status(meta.statusOr(defaultStatus))
                        .header("Content-Type", meta.contentTypeOr(version.contentType()));
        meta.headers().forEach(response::header);
        return response.body(body);
    }

    private ServerResponse fault(SoapVersion version, String code, String message, String detail) {
        return ServerResponse.status(version.httpStatusFor(code))
                .header("Content-Type", version.contentType())
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
        return ServerResponse.ok()
                .header("Content-Type", "text/xml;charset=UTF-8")
                .body(service.wsdlServedFrom(endpoint(request, service)));
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
