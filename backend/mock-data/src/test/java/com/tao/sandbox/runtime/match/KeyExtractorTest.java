package com.tao.sandbox.runtime.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What identity is read out of a request, and when that counts as enough.
 *
 * <p>Extraction is an allowlist: only declared keys are read, so a client adding a correlation id
 * or a timestamp cannot change which mock answers. These pin both halves of that — what is taken,
 * and what {@code satisfied} means for each strategy — because the same rules are applied a second
 * time when the control panel computes a filename, and the two drifting apart produces a file that
 * exists and no request can reach.
 */
class KeyExtractorTest {

    private final KeyExtractor extractor = new KeyExtractor();

    private static RequestFacade request(Map<String, String> pathVariables, Map<String, String> query) {
        return new DescribedRequestFacade(pathVariables, query, Map.of(), null);
    }

    @Test
    void takesEveryDeclaredKeyInDeclarationOrder() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("path:region"), KeySpec.parse("path:orderId")),
                        KeyStrategy.ALL,
                        request(Map.of("orderId", "42", "region", "eu"), Map.of()));

        assertThat(extraction.satisfied()).isTrue();
        // Declaration order, not the order the request happened to carry them in: the filename is
        // built from this map, so its ordering is part of the address.
        assertThat(extraction.keys()).containsExactly(Map.entry("region", "eu"), Map.entry("orderId", "42"));
    }

    /** Anything the request carries that no key declares is not read at all. */
    @Test
    void ignoresFieldsNoKeyDeclares() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("query:limit")),
                        KeyStrategy.ALL,
                        request(Map.of(), Map.of("limit", "10", "correlationId", "abc-123")));

        assertThat(extraction.keys()).containsExactlyEntriesOf(Map.of("limit", "10"));
    }

    /**
     * Under ALL a missing key means the request does not identify a specific mock, so it resolves
     * through the operation's default. What was found is still reported — the trace is what turns
     * a miss into "the key you meant is not the one you declared".
     */
    @Test
    void underAllOneMissingKeyLeavesTheExtractionUnsatisfied() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("path:region"), KeySpec.parse("path:orderId")),
                        KeyStrategy.ALL,
                        request(Map.of("region", "eu"), Map.of()));

        assertThat(extraction.satisfied()).isFalse();
        assertThat(extraction.keys()).containsExactlyEntriesOf(Map.of("region", "eu"));
    }

    /**
     * Declaration order is the tie-break, so a request carrying several declared identifiers
     * resolves deterministically and the trace has one line to explain. Anything after the first
     * present key is not read — which is why a filename must not contain it either.
     */
    @Test
    void underFirstPresentTakesOnlyTheFirstDeclaredKeyThatIsPresent() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("query:orderId"), KeySpec.parse("query:reference")),
                        KeyStrategy.FIRST_PRESENT,
                        request(Map.of(), Map.of("orderId", "42", "reference", "REF-9")));

        assertThat(extraction.satisfied()).isTrue();
        assertThat(extraction.keys()).containsExactlyEntriesOf(Map.of("orderId", "42"));
    }

    @Test
    void underFirstPresentSkipsPastAKeyThatIsAbsent() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("query:orderId"), KeySpec.parse("query:reference")),
                        KeyStrategy.FIRST_PRESENT,
                        request(Map.of(), Map.of("reference", "REF-9")));

        assertThat(extraction.satisfied()).isTrue();
        assertThat(extraction.keys()).containsExactlyEntriesOf(Map.of("reference", "REF-9"));
    }

    @Test
    void underFirstPresentNoDeclaredKeyAtAllIsUnsatisfied() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("query:orderId")),
                        KeyStrategy.FIRST_PRESENT,
                        request(Map.of(), Map.of("correlationId", "abc-123")));

        assertThat(extraction.satisfied()).isFalse();
        assertThat(extraction.keys()).isEmpty();
    }

    /**
     * The same normalisation the control panel applies when it names a file. If extraction stopped
     * doing this, every zero-padded caller would miss the mock written for the unpadded form.
     */
    @Test
    void normalisesEveryValueItTakes() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("path:orderId")),
                        KeyStrategy.ALL,
                        request(Map.of("orderId", "  00042 "), Map.of()));

        assertThat(extraction.keys()).containsExactlyEntriesOf(Map.of("orderId", "42"));
    }

    /** An empty value is absent, so under ALL it leaves the extraction unsatisfied. */
    @Test
    void treatsAnEmptyValueAsAbsentRatherThanAsAKey() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("query:limit")),
                        KeyStrategy.ALL,
                        request(Map.of(), Map.of("limit", "  ")));

        assertThat(extraction.satisfied()).isFalse();
        assertThat(extraction.keys()).isEmpty();
    }

    /** An operation may declare a body key; the facade reads it by expression, not by name. */
    @Test
    void readsAKeyFromAJsonBody() {
        var extraction =
                extractor.extract(
                        List.of(KeySpec.parse("body:$.customer.accountId")),
                        KeyStrategy.ALL,
                        new DescribedRequestFacade(
                                Map.of(), Map.of(), Map.of(), "{\"customer\":{\"accountId\":\"00099\"}}"));

        assertThat(extraction.satisfied()).isTrue();
        assertThat(extraction.keys()).containsExactlyEntriesOf(Map.of("accountId", "99"));
    }
}
