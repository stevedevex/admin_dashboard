package com.tao.sandbox.store;

import java.util.List;
import java.util.Optional;

/** Hot path. The only thing request handling needs. */
public interface MockProvider {

    /**
     * Resolve a query, walking the scenario inheritance chain.
     *
     * @return the document and where it was found, or empty if nothing matched
     */
    Optional<Resolved> resolve(MockQuery query);

    /**
     * @param scenarioId the scenario the document was actually found in, which may be an ancestor
     *     of the one asked for
     */
    record Resolved(MockId id, MockDocument document, String scenarioId, boolean inherited) {}

    /** Candidate paths this provider would try for a query, most specific first. */
    List<String> candidates(MockQuery query);
}
