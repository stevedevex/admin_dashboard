package com.tao.sandbox.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

/**
 * Writing a filename from keys, and reading one back.
 *
 * <p>The round trip is the property that matters. Resolution writes a name from a request; anything
 * asking <em>which requests could this file answer</em> reads one from disk. If the two disagree, a
 * mock is judged reachable when it is not, or the reverse — and both readings are silent.
 */
class MockStemTest {

    @Test
    void writesKeysInTheOrderGiven() {
        assertThat(MockStem.of(keys("brid", "1001", "bc", "ch100"))).isEqualTo("brid=1001&bc=ch100");
    }

    /** Lowercased: macOS and SMB are case-insensitive, Linux CI is not. */
    @Test
    void writesLowercase() {
        assertThat(MockStem.of(keys("BRID", "CH100"))).isEqualTo("brid=ch100");
    }

    @Test
    void noKeysWritesNothing() {
        assertThat(MockStem.of(keys())).isEmpty();
    }

    @Test
    void readsBackWhatItWrote() {
        SequencedMap<String, String> original = keys("brid", "1001", "bc", "ch100");

        assertThat(MockStem.parse(MockStem.of(original)))
                .contains(keys("brid", "1001", "bc", "ch100"));
    }

    @Test
    void readsBackASingleKey() {
        assertThat(MockStem.parse("brid=1001")).contains(keys("brid", "1001"));
    }

    /**
     * The fallback is a name with no keys rather than a special case: zero required pairs is
     * exactly "matches every request for this operation".
     */
    @Test
    void theDefaultReadsAsNoKeysAtAll() {
        assertThat(MockStem.parse("_default")).contains(new LinkedHashMap<>());
    }

    /** A value may contain dots and hyphens; only the separators are structural. */
    @Test
    void readsValuesThatContainOrdinaryPunctuation() {
        assertThat(MockStem.parse("price=999.99&ref=ac-0100"))
                .contains(keys("price", "999.99", "ref", "ac-0100"));
    }

    /**
     * A name that is not key=value shaped is a file no request produces a name for. Answering empty
     * rather than guessing is what lets reachability report it instead of silently accepting it.
     */
    @Test
    void refusesToReadANameThatIsNotKeyValueShaped() {
        assertThat(MockStem.parse("whatever")).isEmpty();
        assertThat(MockStem.parse("brid=1001&broken")).isEmpty();
        assertThat(MockStem.parse("=1001")).isEmpty();
        assertThat(MockStem.parse("brid=")).isEmpty();
        assertThat(MockStem.parse("")).isEmpty();
        assertThat(MockStem.parse(null)).isEmpty();
    }

    // --- what cannot be written --------------------------------------------

    /**
     * A separator inside a value produces a name that reads back as different keys than it was
     * written from. Refused where a message can say so, rather than escaped — escaping would rename
     * every file that already exists.
     */
    @Test
    void reportsAValueCarryingASeparator() {
        assertThat(MockStem.problemWith(Map.of("ref", "a&b"))).isPresent();
        assertThat(MockStem.problemWith(Map.of("ref", "a=b"))).isPresent();
        assertThat(MockStem.problemWith(Map.of("a&b", "value"))).isPresent();
    }

    @Test
    void ordinaryValuesRaiseNoProblem() {
        assertThat(MockStem.problemWith(Map.of("brid", "1001", "price", "999.99"))).isEmpty();
    }

    // --- what cannot be stored ---------------------------------------------

    @Test
    void reportsANameTooLongForTheFilesystem() {
        String tooLong = "k=" + "v".repeat(MockStem.MAX_BYTES) + ".xml";

        assertThat(MockStem.problemWithLength(tooLong)).isPresent().get().asString().contains("aliases");
    }

    @Test
    void anOrdinaryNameFits() {
        assertThat(MockStem.problemWithLength("brid=1001&bc=ch100.xml")).isEmpty();
    }

    /** Measured in bytes, because that is what the filesystem limits. */
    @Test
    void multiByteCharactersCountAsTheBytesTheyAre() {
        String name = "k=" + "é".repeat(MockStem.MAX_BYTES / 2);

        assertThat(name.length()).isLessThan(MockStem.MAX_BYTES);
        assertThat(MockStem.problemWithLength(name)).isPresent();
    }

    private static SequencedMap<String, String> keys(String... pairs) {
        SequencedMap<String, String> keys = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            keys.put(pairs[i], pairs[i + 1]);
        }
        return keys;
    }
}
