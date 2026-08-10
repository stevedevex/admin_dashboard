package com.tao.sandbox.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * What a caller is asking for, expressed without reference to how it is stored.
 *
 * <p>This is the boundary that keeps the storage choice from leaking upward. A filesystem
 * provider turns a query into a path; a document-store provider turns it into a query document.
 * Nothing above this record knows which is in use.
 *
 * @param keys extracted identity, normalised, in declared priority order
 * @param matching how the stored library should be searched with those keys. Part of the ask
 *     rather than of the store: the operation's configuration decides whether a file must name
 *     every key the request carried or may name a subset of them, and a provider that had to
 *     work that out for itself would be reading configuration it has no business knowing about.
 */
public record MockQuery(
        String scenarioId,
        String serviceId,
        String operationId,
        SequencedMap<String, String> keys,
        Matching matching) {

    /** How closely a stored name has to correspond to the keys a request carried. */
    public enum Matching {
        /** The name is exactly these keys, or it is the operation's default. */
        EXACT,
        /**
         * The name may be any subset of these keys, and the largest matching subset wins. See
         * {@code KeyStrategy.BEST_MATCH}.
         */
        BEST
    }

    public MockQuery {
        keys = new LinkedHashMap<>(keys);
    }

    /** Exact matching, which is what every caller wanted before subsets existed. */
    public MockQuery(
            String scenarioId, String serviceId, String operationId, SequencedMap<String, String> keys) {
        this(scenarioId, serviceId, operationId, keys, Matching.EXACT);
    }

    @Override
    public SequencedMap<String, String> keys() {
        return Collections.unmodifiableSequencedMap(keys);
    }

    /**
     * The key portion of a filename, e.g. {@code petid=1&region=eu}. See {@link MockStem}, which
     * also reads one back — the two directions have to agree, so they live together.
     */
    public String keySignature() {
        return MockStem.of(keys);
    }
}
