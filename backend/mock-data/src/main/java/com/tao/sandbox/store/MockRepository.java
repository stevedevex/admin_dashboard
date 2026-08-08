package com.tao.sandbox.store;

import java.util.List;
import java.util.Optional;

/** Control plane. Used by the admin API, never by request handling. */
public interface MockRepository extends MockProvider {

    /** Metadata only — bodies are never loaded for browsing. */
    List<MockSummary> list(String scenarioId, String serviceId);

    Optional<MockDocument> get(MockId id);

    MockSummary save(MockId id, String body);

    void delete(MockId id);

    List<Scenario> scenarios();

    /**
     * Re-read from the underlying store.
     *
     * <p>Explicit rather than watched: a mounted network share gives no change notification, and
     * behaviour that works on a laptop but not in the deployed instance is worse than none.
     */
    void reload();
}
