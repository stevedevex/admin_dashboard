package com.tao.sandbox.spec;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.config.SandboxProperties.ServiceConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.openapi.OpenApiSpecLoader;
import com.tao.sandbox.spec.wsdl.SoapSchemas;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import com.tao.sandbox.spec.wsdl.WsdlSpecLoader;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Every operation the sandbox serves, resolved once at startup.
 *
 * <p>Specs are parsed here and nowhere else. Request handling reads this registry, so a
 * misconfigured service fails at boot with the full list of problems rather than on whichever
 * request happens to hit it first.
 */
@Component
public class SpecRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpecRegistry.class);

    private final SandboxProperties properties;
    private final OpenApiSpecLoader openApiLoader;
    private final WsdlSpecLoader wsdlLoader;

    private final DefaultResourceLoader resources = new DefaultResourceLoader();

    private final Map<String, OperationDefinition> restByKey = new LinkedHashMap<>();
    private final Map<String, SoapServiceDefinition> soapByService = new LinkedHashMap<>();
    private final Map<String, String> responseSchemas = new LinkedHashMap<>();
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private final List<ServiceDescriptor> descriptors = new ArrayList<>();

    /** A dropped-in contract, exactly as its author published it. */
    public record Contract(String content, String mediaType) {}

    public SpecRegistry(
            SandboxProperties properties, OpenApiSpecLoader openApiLoader, WsdlSpecLoader wsdlLoader) {
        this.properties = properties;
        this.openApiLoader = openApiLoader;
        this.wsdlLoader = wsdlLoader;
    }

    @PostConstruct
    void load() {
        List<String> problems = new ArrayList<>();

        for (ServiceConfig service : properties.services()) {
            if (service.type() == ServiceType.REST) {
                OpenApiSpecLoader.Loaded loaded = openApiLoader.load(service, problems);
                loaded.operations().forEach(op -> restByKey.put(key(op.serviceId(), op.operationId()), op));
                loaded.responseSchemas()
                        .forEach((operationId, schema) -> responseSchemas.put(key(service.id(), operationId), schema));
                descriptors.add(describeRest(service, loaded.operations()));
                retainRestContract(service, loaded.serverUrls(), problems);
            } else {
                SoapServiceDefinition definition = wsdlLoader.load(service, problems);
                if (definition != null) {
                    soapByService.put(definition.serviceId(), definition);
                    descriptors.add(describeSoap(service, definition));
                    contracts.put(service.id(), new Contract(definition.wsdl(), "text/xml;charset=UTF-8"));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new SpecLoadException(problems);
        }

        descriptors.forEach(
                service ->
                        log.info(
                                "Serving {} \"{}\" ({}) at {} — {} operation(s)",
                                service.id(),
                                service.name(),
                                service.type(),
                                service.endpoint(),
                                service.operations().size()));
    }

    private ServiceDescriptor describeRest(ServiceConfig service, List<OperationDefinition> operations) {
        return new ServiceDescriptor(
                service.id(),
                service.name(),
                service.type(),
                service.basePath(),
                service.spec(),
                operations.stream()
                        .map(
                                op ->
                                        new ServiceDescriptor.OperationSummary(
                                                op.operationId(),
                                                op.method().name(),
                                                op.path(),
                                                op.keys().stream().map(KeyDescriptor::of).toList(),
                                                op.strategy()))
                        .toList());
    }

    private ServiceDescriptor describeSoap(ServiceConfig service, SoapServiceDefinition definition) {
        return new ServiceDescriptor(
                service.id(),
                service.name(),
                service.type(),
                definition.path(),
                service.wsdl(),
                definition.served().values().stream()
                        .map(
                                op ->
                                        new ServiceDescriptor.OperationSummary(
                                                op.operationId(),
                                                "POST",
                                                definition.path(),
                                                op.keys().stream().map(KeyDescriptor::of).toList(),
                                                op.strategy()))
                        .toList());
    }

    /** Everything the sandbox serves, described for the dashboard. */
    public List<ServiceDescriptor> services() {
        return List.copyOf(descriptors);
    }

    public List<OperationDefinition> restOperations() {
        return List.copyOf(restByKey.values());
    }

    public List<SoapServiceDefinition> soapServices() {
        return List.copyOf(soapByService.values());
    }

    public Optional<ServiceDescriptor> findService(String serviceId) {
        return descriptors.stream().filter(service -> service.id().equals(serviceId)).findFirst();
    }

    public Optional<OperationDefinition> findRest(String serviceId, String operationId) {
        return Optional.ofNullable(restByKey.get(key(serviceId, operationId)));
    }

    /**
     * One served operation, whatever its protocol.
     *
     * <p>Narrowed to {@link ServedOperation} because the control panel's questions — what are the
     * keys, which strategy applies — are the protocol-independent ones. A caller that needs the
     * REST route or the SOAP binding asks {@link #findRest} or {@link #soapServices} instead.
     */
    public Optional<ServedOperation> findOperation(String serviceId, String operationId) {
        OperationDefinition rest = restByKey.get(key(serviceId, operationId));
        if (rest != null) {
            return Optional.of(rest);
        }

        SoapServiceDefinition soap = soapByService.get(serviceId);
        return soap == null ? Optional.empty() : Optional.ofNullable(soap.served().get(operationId));
    }

    /** The XSD a SOAP service declares, and each operation's response element. */
    public Optional<SoapSchemas> soapSchemas(String serviceId) {
        return Optional.ofNullable(soapByService.get(serviceId)).map(SoapServiceDefinition::schemas);
    }

    /**
     * Whether validation has anything to check this service's payloads against — a compiled XSD
     * for SOAP, at least one declared response schema for REST. The dashboard renders this as a
     * fact per service; computing it client-side would mean the client re-deriving what
     * "checkable" means.
     */
    public boolean hasSchema(String serviceId) {
        SoapServiceDefinition soap = soapByService.get(serviceId);
        if (soap != null) {
            return soap.schemas() != null && soap.schemas().compiled().isPresent();
        }
        return responseSchemas.keySet().stream().anyMatch(key -> key.startsWith(serviceId + "/"));
    }

    /**
     * The success response's schema, for operations whose contract declares one.
     *
     * <p>Empty is a normal answer, not a fault: a WSDL's response schema is not extracted yet, and
     * plenty of REST operations declare no response body at all.
     */
    public Optional<String> findResponseSchema(String serviceId, String operationId) {
        return Optional.ofNullable(responseSchemas.get(key(serviceId, operationId)));
    }

    /**
     * The contract as it was dropped in — the OpenAPI document for a REST service, the WSDL for a
     * SOAP one — with one deliberate change: a REST document's server address points at the
     * sandbox's mount instead of wherever the author published. The same rule the {@code ?wsdl}
     * endpoint applies, for the same reason: a client that resolves its endpoint from the served
     * contract must land here, not on production.
     *
     * <p>The SOAP content here is the raw WSDL, address and all — the {@code ?wsdl} endpoint on
     * the service itself serves the rewritten one a client should actually consume.
     */
    public Optional<Contract> contract(String serviceId) {
        return Optional.ofNullable(contracts.get(serviceId));
    }

    /**
     * Retained at load, from the same location the parser read: the parser hands back a model,
     * not the author's bytes, and the served contract should stay the author's document — so the
     * server address is corrected by targeted text surgery, never by re-serialising the model.
     */
    private void retainRestContract(ServiceConfig service, List<String> serverUrls, List<String> problems) {
        if (service.spec() == null || service.spec().isBlank()) {
            return; // already reported by the loader
        }

        try (InputStream in = resources.getResource(service.spec()).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            content = pointServersAtSandbox(content, service, serverUrls);
            contracts.put(service.id(), new Contract(content, contractMediaType(service.spec(), content)));
        } catch (IOException e) {
            problems.add(
                    "%s: could not read %s for serving — %s".formatted(service.id(), service.spec(), e.getMessage()));
        }
    }

    /**
     * The mount is emitted as a relative URL, so it resolves against whichever host the client
     * fetched the contract from — no per-request endpoint computation, and it stays correct
     * behind whatever fronts a deployed instance.
     *
     * <p>Three shapes, three treatments: an absolute declared server is string-replaced, exactly
     * as {@code ?wsdl} replaces {@code soap:address}; a document with no {@code servers} at all
     * gets a block appended when it is YAML (legal at any top-level position — a JSON document
     * cannot be appended to without re-serialising, so it is left alone); a relative declared
     * server is already resolved against this host and is left as the author wrote it.
     */
    private static String pointServersAtSandbox(String content, ServiceConfig service, List<String> serverUrls) {
        String mount = service.basePath() == null || service.basePath().isBlank() ? "/" : service.basePath();

        boolean rewritten = false;
        for (String url : serverUrls) {
            if (url.contains("://") && content.contains(url)) {
                content = content.replace(url, mount);
                rewritten = true;
            }
        }

        boolean isJson = content.stripLeading().startsWith("{");
        if (!rewritten && serverUrls.isEmpty() && !isJson) {
            content =
                    content.stripTrailing()
                            + "\n\n# Added by the sandbox: requests belong at its mount, not at the author's host.\n"
                            + "servers:\n- url: \"%s\"\n".formatted(mount);
        }

        return content;
    }

    /** OpenAPI documents come as YAML or JSON; say which, so editors highlight correctly. */
    private static String contractMediaType(String location, String content) {
        return location.endsWith(".json") || content.stripLeading().startsWith("{")
                ? "application/json"
                : "application/yaml";
    }

    private static String key(String serviceId, String operationId) {
        return serviceId + "/" + operationId;
    }
}
