package com.tao.sandbox.spec;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.config.SandboxProperties.ServiceConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.openapi.OpenApiSpecLoader;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import com.tao.sandbox.spec.wsdl.WsdlSpecLoader;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final Map<String, OperationDefinition> restByKey = new LinkedHashMap<>();
    private final Map<String, SoapServiceDefinition> soapByService = new LinkedHashMap<>();
    private final Map<String, String> responseSchemas = new LinkedHashMap<>();
    private final List<ServiceDescriptor> descriptors = new ArrayList<>();

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
            } else {
                SoapServiceDefinition definition = wsdlLoader.load(service, problems);
                if (definition != null) {
                    soapByService.put(definition.serviceId(), definition);
                    descriptors.add(describeSoap(service, definition));
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
                                                op.keys().stream().map(KeyDescriptor::of).toList()))
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
                                                op.keys().stream().map(KeyDescriptor::of).toList()))
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
    public Optional<com.tao.sandbox.spec.wsdl.SoapSchemas> soapSchemas(String serviceId) {
        return Optional.ofNullable(soapByService.get(serviceId)).map(SoapServiceDefinition::schemas);
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

    private static String key(String serviceId, String operationId) {
        return serviceId + "/" + operationId;
    }
}
