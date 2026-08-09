package com.tao.sandbox.control;

import com.tao.sandbox.control.view.SchemaView;
import com.tao.sandbox.spec.ServiceDescriptor;
import com.tao.sandbox.spec.SpecRegistry;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything the sandbox serves, described for a reader rather than for the resolver. */
@RestController
@RequestMapping(value = "/__tao/services", produces = MediaType.APPLICATION_JSON_VALUE)
class ServiceCatalogController {

    private final SpecRegistry registry;

    ServiceCatalogController(SpecRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returned straight from the registry: {@link ServiceDescriptor} was written as the
     * control-plane shape, so a view record here would only be a second place for the two to
     * disagree.
     */
    @GetMapping
    List<ServiceDescriptor> services() {
        return registry.services();
    }

    /**
     * The dropped-in contract — OpenAPI document for REST, WSDL for SOAP — so the client team
     * fetches what they integrate against from the same host they call. Served with the
     * contract's own media type, not this controller's JSON; a REST document's server address
     * points at the sandbox mount, per {@link SpecRegistry#contract}.
     */
    @GetMapping(value = "/{serviceId}/spec", produces = MediaType.ALL_VALUE)
    ResponseEntity<String> spec(@PathVariable String serviceId) {
        SpecRegistry.Contract contract =
                registry
                        .contract(serviceId)
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.notFound(
                                                "service-not-found",
                                                "No such service",
                                                "'%s' is not one of %s"
                                                        .formatted(
                                                                serviceId,
                                                                registry.services().stream()
                                                                        .map(ServiceDescriptor::id)
                                                                        .toList())));

        return ResponseEntity.ok().header("Content-Type", contract.mediaType()).body(contract.content());
    }

    @GetMapping("/{serviceId}/operations/{operationId}/schema")
    SchemaView schema(@PathVariable String serviceId, @PathVariable String operationId) {
        if (registry.findOperation(serviceId, operationId).isEmpty()) {
            throw ControlPanelProblem.notFound(
                    "operation-not-found",
                    "No such operation",
                    "%s/%s is not served. %s".formatted(serviceId, operationId, servedElsewhere(serviceId)));
        }

        if (registry.findRest(serviceId, operationId).isPresent()) {
            return SchemaView.json(registry.findResponseSchema(serviceId, operationId).orElse(null));
        }

        return registry
                .soapSchemas(serviceId)
                .map(
                        schemas ->
                                SchemaView.xsd(
                                        schemas.documentFor(operationId).orElse(null),
                                        schemas.unavailable() != null
                                                ? schemas.unavailable()
                                                : "The WSDL declares no response element for this operation"))
                .orElseGet(() -> SchemaView.xsd(null, "No schema was read for this service"));
    }

    /** Naming the alternatives turns a typo into a correction rather than an investigation. */
    private String servedElsewhere(String serviceId) {
        return registry
                .findService(serviceId)
                .map(
                        service ->
                                "Operations on %s: %s"
                                        .formatted(
                                                serviceId,
                                                service.operations().stream()
                                                        .map(ServiceDescriptor.OperationSummary::id)
                                                        .toList()))
                .orElseGet(
                        () ->
                                "No such service. Services: "
                                        + registry.services().stream().map(ServiceDescriptor::id).toList());
    }
}
