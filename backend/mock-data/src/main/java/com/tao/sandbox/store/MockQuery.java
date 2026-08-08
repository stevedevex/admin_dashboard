package com.tao.sandbox.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

/**
 * What a caller is asking for, expressed without reference to how it is stored.
 *
 * <p>This is the boundary that keeps the storage choice from leaking upward. A filesystem
 * provider turns a query into a path; a document-store provider turns it into a query document.
 * Nothing above this record knows which is in use.
 *
 * @param keys extracted identity, normalised, in declared priority order
 */
public record MockQuery(
        String scenarioId, String serviceId, String operationId, SequencedMap<String, String> keys) {

    public MockQuery {
        keys = new LinkedHashMap<>(keys);
    }

    @Override
    public SequencedMap<String, String> keys() {
        return java.util.Collections.unmodifiableSequencedMap(keys);
    }

    /**
     * The key portion of a filename, e.g. {@code petid=1&region=eu}.
     *
     * <p>Lowercased because macOS and SMB shares are case-insensitive while Linux CI is not: a
     * mock authored on a laptop must not vanish in a container.
     */
    public String keySignature() {
        if (keys.isEmpty()) {
            return "";
        }
        var joined = new StringBuilder();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (!joined.isEmpty()) {
                joined.append('&');
            }
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return joined.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
