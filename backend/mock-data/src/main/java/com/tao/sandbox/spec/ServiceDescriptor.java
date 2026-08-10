package com.tao.sandbox.spec;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
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
     * @param strategy how many of those keys a filename carries. Reported because the answer
     *     changes what an author is being asked for: under {@code ALL} a blank key is an omission,
     *     under {@code BEST_MATCH} it is the point — the file matches whatever that field happens
     *     to be. A dashboard that cannot tell them apart can only offer the strictest reading, and
     *     then a subset mock is something you can drop into the store but not write from here.
     */
    public record OperationSummary(
            String id, String method, String path, List<KeyDescriptor> keys, KeyStrategy strategy) {}
}
