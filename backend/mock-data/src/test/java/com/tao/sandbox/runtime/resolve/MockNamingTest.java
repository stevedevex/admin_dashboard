package com.tao.sandbox.runtime.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.spec.ServedOperation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

/**
 * The naming rules, checked directly rather than through whichever endpoint happens to use them.
 *
 * <p>A mock is reachable only if the name it was saved under is the name resolution computes from
 * a request. These assert the saving half; {@code KeyExtractorTest} asserts the reading half, and
 * both go through the same {@link KeyStrategy} — which is what makes agreement structural rather
 * than a coincidence maintained by hand.
 */
class MockNamingTest {

    // --- stems --------------------------------------------------------------

    @Test
    void namesAMockAfterItsKeysInDeclarationOrder() {
        assertThat(MockNaming.stemFor("svc", "op", keys("region", "eu", "orderId", "42")))
                .isEqualTo("region=eu&orderid=42");
    }

    /**
     * No keys names the fallback, which is a mock an author legitimately wants to write — not a
     * mistake to be refused.
     */
    @Test
    void noKeysNamesTheOperationsDefault() {
        assertThat(MockNaming.stemFor("svc", "op", keys())).isEqualTo("_default");
    }

    /**
     * Lowercased, because macOS and SMB shares are case-insensitive while Linux CI is not: a mock
     * authored on a laptop must not vanish in a container.
     */
    @Test
    void namesAreLowercased() {
        assertThat(MockNaming.stemFor("svc", "op", keys("Reference", "REF-9"))).isEqualTo("reference=ref-9");
    }

    // --- matching supplied values against declared keys ---------------------

    @Test
    void acceptsAKeyUnderItsDerivedName() {
        assertThat(MockNaming.resolveKeys(operation(KeyStrategy.ALL, "path:orderId"), Map.of("orderId", "42")))
                .containsExactlyEntriesOf(Map.of("orderId", "42"));
    }

    /**
     * The services endpoint reports a key's name, its expression and its full declaration, and a
     * caller holding any of the three means the same field. Refusing two of them would quietly
     * produce {@code _default} — a wrong answer wearing the shape of a right one.
     */
    @Test
    void acceptsTheSameKeyUnderItsExpressionOrItsWholeDeclaration() {
        ServedOperation operation = operation(KeyStrategy.ALL, "body:$.customer.accountId");

        assertThat(MockNaming.resolveKeys(operation, Map.of("$.customer.accountId", "42")))
                .containsExactlyEntriesOf(Map.of("accountId", "42"));
        assertThat(MockNaming.resolveKeys(operation, Map.of("BODY:$.customer.accountId", "42")))
                .containsExactlyEntriesOf(Map.of("accountId", "42"));
    }

    /** The same normalisation extraction applies, or the file is named for a value no request produces. */
    @Test
    void normalisesSuppliedValues() {
        assertThat(MockNaming.resolveKeys(operation(KeyStrategy.ALL, "path:orderId"), Map.of("orderId", " 00042 ")))
                .containsExactlyEntriesOf(Map.of("orderId", "42"));
    }

    @Test
    void ignoresValuesForFieldsTheOperationDoesNotDeclare() {
        assertThat(MockNaming.resolveKeys(operation(KeyStrategy.ALL, "path:orderId"), Map.of("correlationId", "abc")))
                .isEmpty();
    }

    @Test
    void toleratesNoSuppliedKeysAtAll() {
        assertThat(MockNaming.resolveKeys(operation(KeyStrategy.ALL, "path:orderId"), null)).isEmpty();
    }

    /** Nothing past the first present key is read from a request, so none of it belongs in a name. */
    @Test
    void underFirstPresentNamesOnlyTheFirstDeclaredKeySupplied() {
        SequencedMap<String, String> resolved =
                MockNaming.resolveKeys(
                        operation(KeyStrategy.FIRST_PRESENT, "query:orderId", "query:reference"),
                        Map.of("orderId", "42", "reference", "REF-9"));

        assertThat(resolved).containsExactlyEntriesOf(Map.of("orderId", "42"));
    }

    // --- whether a name may be written at all -------------------------------

    @Test
    void underAllEveryDeclaredKeyMustBePresent() {
        ServedOperation operation = operation(KeyStrategy.ALL, "path:region", "path:orderId");

        assertThat(MockNaming.satisfies(operation, keys("region", "eu", "orderId", "42"))).isTrue();
        // A file named from a subset can never be reached: no request satisfying fewer keys
        // produces this signature.
        assertThat(MockNaming.satisfies(operation, keys("region", "eu"))).isFalse();
    }

    @Test
    void underFirstPresentOneKeyIsEnough() {
        ServedOperation operation = operation(KeyStrategy.FIRST_PRESENT, "query:orderId", "query:reference");

        assertThat(MockNaming.satisfies(operation, keys("orderId", "42"))).isTrue();
        assertThat(MockNaming.satisfies(operation, keys())).isFalse();
    }

    // --- what the caller is told the name became ----------------------------

    @Test
    void reportsKeysAsTheyWereWrittenIntoTheName() {
        assertThat(MockNaming.asWritten(keys("reference", "REF-9")))
                .containsExactlyEntriesOf(Map.of("reference", "ref-9"));
    }

    // --- fixtures -----------------------------------------------------------

    private static SequencedMap<String, String> keys(String... pairs) {
        SequencedMap<String, String> keys = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            keys.put(pairs[i], pairs[i + 1]);
        }
        return keys;
    }

    private static ServedOperation operation(KeyStrategy strategy, String... declarations) {
        List<KeySpec> keys = List.of(declarations).stream().map(KeySpec::parse).toList();

        return new ServedOperation() {
            @Override
            public String serviceId() {
                return "svc";
            }

            @Override
            public String operationId() {
                return "op";
            }

            @Override
            public List<KeySpec> keys() {
                return keys;
            }

            @Override
            public KeyStrategy strategy() {
                return strategy;
            }
        };
    }
}
