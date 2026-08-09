package com.tao.sandbox.control.view;

import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.ServiceDescriptor;
import java.util.List;

/**
 * One served service, with the facts the dashboard renders beside the contract-derived shape:
 * whether validation has a schema to check against, how the payloads are written, and how many
 * mocks are visible in the active scenario.
 *
 * @param format how this service's payloads are written, for editor highlighting
 * @param mockCount mocks visible in the active scenario, inherited included
 */
public record ServiceView(
        String id,
        String name,
        ServiceType type,
        String endpoint,
        String specLocation,
        String format,
        boolean hasSchema,
        int mockCount,
        List<ServiceDescriptor.OperationSummary> operations) {

    public static ServiceView of(
            ServiceDescriptor descriptor, boolean hasSchema, int mockCount) {
        return new ServiceView(
                descriptor.id(),
                descriptor.name(),
                descriptor.type(),
                descriptor.endpoint(),
                descriptor.specLocation(),
                descriptor.type() == ServiceType.SOAP ? "xml" : "json",
                hasSchema,
                mockCount,
                descriptor.operations());
    }
}
