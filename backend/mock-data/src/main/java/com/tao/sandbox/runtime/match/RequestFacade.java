package com.tao.sandbox.runtime.match;

import java.util.List;
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

    /**
     * Names the request carries that a key could have read.
     *
     * <p>Used only by the dry run, to show what was discarded — the correlation ids, timestamps and
     * optional parameters that extraction deliberately ignores. Seeing them listed is what turns
     * "the mock did not match" into "of course, the key is not what I thought".
     *
     * <p>Empty by default so the live request path pays nothing for it: enumerating a body means
     * parsing one, and operations that declare no {@code body:} key never touch theirs.
     */
    default List<String> fieldNames() {
        return List.of();
    }

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
