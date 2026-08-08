package com.tao.sandbox.spec;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.runtime.match.KeySpec;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * One operation the sandbox serves, after config and spec have been reconciled.
 *
 * <p>Only served operations become definitions. Anything present in the spec but absent from
 * configuration is never routed and answers NOT_IMPLEMENTED — a forty-operation contract does not
 * silently become forty half-working endpoints.
 *
 * @param successStatus the status the contract declares for success, so a {@code POST} answers 201
 *     without anyone configuring it
 * @param responseContentType the media type the contract declares, rather than assuming JSON
 */
public record OperationDefinition(
        String serviceId,
        String operationId,
        HttpMethod method,
        /** Full path including the service base path, as an RFC 6570-style template. */
        String path,
        int successStatus,
        String responseContentType,
        List<KeySpec> keys,
        KeyStrategy strategy)
        implements ServedOperation {}
