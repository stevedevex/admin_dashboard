package com.tao.sandbox.runtime.match;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import org.springframework.stereotype.Component;

/**
 * Pulls declared identity out of a request and discards everything else.
 *
 * <p>Extraction is an allowlist. Only declared keys are read, so a client adding an unexpected
 * field — a correlation id, a new optional parameter, a date range that moves every day — cannot
 * change which mock is served. Comparing whole payloads instead would break on exactly those.
 */
@Component
public class KeyExtractor {

    public record Extraction(SequencedMap<String, String> keys, boolean satisfied) {}

    public Extraction extract(List<KeySpec> specs, KeyStrategy strategy, RequestFacade request) {
        SequencedMap<String, String> extracted = new LinkedHashMap<>();

        for (KeySpec spec : specs) {
            Optional<String> value = request.read(spec).flatMap(Normaliser::normalise);

            if (value.isEmpty()) {
                continue;
            }

            extracted.put(spec.name(), value.get());

            if (strategy == KeyStrategy.FIRST_PRESENT) {
                // Declaration order is the tie-break. A request carrying several identifiers
                // resolves by the first declared one, so the outcome is deterministic and the
                // trace has a single line to explain.
                return new Extraction(extracted, true);
            }
        }

        boolean satisfied =
                switch (strategy) {
                    case ALL -> extracted.size() == specs.size();
                    case FIRST_PRESENT -> !extracted.isEmpty();
                };

        return new Extraction(extracted, satisfied);
    }
}
