package com.tao.sandbox.store;

import java.util.List;
import java.util.Optional;

/** Control plane. Used by the admin API, never by request handling. */
public interface MockRepository extends MockProvider {

    /**
     * Filename stem of an operation's fallback mock — the one resolution tries when no
     * key-specific file matches. One constant, because the store resolves it and the control
     * panel computes names with it, and the two must never disagree.
     */
    String DEFAULT_STEM = "_default";

    /** Metadata only — bodies are never loaded for browsing. */
    List<MockSummary> list(String scenarioId, String serviceId);

    Optional<MockDocument> get(MockId id);

    /**
     * Creates or replaces a mock, payload and sidecars together.
     *
     * <p>One call rather than a payload write plus separate sidecar writes: a save that succeeded
     * for the body and failed for the meta would leave a mock answering with the previous status,
     * which is the kind of half-applied change that is very hard to see.
     */
    MockSummary save(MockId id, MockDocument document);

    /** Removes the payload and its sidecars. A stale sidecar would re-apply to whatever is saved next. */
    void delete(MockId id);

    List<Scenario> scenarios();

    /**
     * @throws IllegalArgumentException if the id is taken, or the parent is unknown or would form a
     *     cycle — all three produce a scenario that cannot serve, so none is created
     */
    Scenario createScenario(String id, String name, String description, String parent);

    /** Removes the scenario and everything it owns. Inherited mocks belong to the parent and stay. */
    void deleteScenario(String id);

    /**
     * Where this store keeps its mocks, for the control panel to display.
     *
     * <p>Free-form on purpose — a directory for the filesystem store, a connection string and
     * collection for a document store. Nothing parses it, so no caller can come to depend on one
     * store's shape.
     */
    String location();

    /**
     * Re-read from the underlying store.
     *
     * <p>Explicit rather than watched: a mounted network share gives no change notification, and
     * behaviour that works on a laptop but not in the deployed instance is worse than none.
     */
    void reload();
}
