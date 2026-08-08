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
                List<OperationDefinition> operations = openApiLoader.load(service, problems);
                operations.forEach(op -> restByKey.put(key(op.serviceId(), op.operationId()), op));
                descriptors.add(describeRest(service, operations));
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
                                                op.keys().stream().map(k -> k.source() + ":" + k.expression()).toList()))
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
                                                op.keys().stream().map(k -> k.source() + ":" + k.expression()).toList()))
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

    public Optional<OperationDefinition> findRest(String serviceId, String operationId) {
        return Optional.ofNullable(restByKey.get(key(serviceId, operationId)));
    }

    private static String key(String serviceId, String operationId) {
        return serviceId + "/" + operationId;
    }
}
