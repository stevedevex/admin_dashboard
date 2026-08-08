package com.tao.sandbox.spec;

import com.tao.sandbox.config.SandboxProperties.ServiceType;
import java.util.List;

/**
 * What the sandbox serves, described for a reader rather than for the resolver.
 *
 * <p>This is the shape the control plane will return from {@code GET /__tao/services}, and the
 * only place the dashboard should need to look to render a service list — hence the display name
 * and the mount point, neither of which request handling has any use for.
 *
 * @param endpoint where a client reaches it: the base path for REST, the single path for SOAP
 */
public record ServiceDescriptor(
        String id,
        String name,
        ServiceType type,
        String endpoint,
        String specLocation,
        List<OperationSummary> operations) {

    /**
     * @param keys the declared identity fields, so the dashboard can offer them when creating a
     *     mock instead of asking anyone to type a file name
     */
    public record OperationSummary(String id, String method, String path, List<String> keys) {}
}
