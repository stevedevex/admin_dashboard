package com.tao.sandbox.runtime.match;

import java.util.Optional;

/**
 * What key extraction needs from an inbound request, independent of protocol.
 *
 * <p>REST and SOAP differ only in how a request is identified and read. Everything downstream —
 * normalisation, lookup, response assembly, tracing — is shared, and this interface is where the
 * two paths converge.
 */
public interface RequestFacade {

    Optional<String> path(String name);

    Optional<String> query(String name);

    Optional<String> header(String name);

    /** JSON body lookup. */
    Optional<String> body(String expression);

    /** Envelope lookup for XML payloads. */
    Optional<String> xpath(String expression);

    default Optional<String> read(KeySpec key) {
        return switch (key.source()) {
            case PATH -> path(key.expression());
            case QUERY -> query(key.expression());
            case HEADER -> header(key.expression());
            case BODY -> body(key.expression());
            case XPATH -> xpath(key.expression());
        };
    }
}
