package com.tao.sandbox.runtime.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether a saved mock is ever reachable.
 *
 * <p>Normalisation is applied in two places — when a key is extracted from a live request, and
 * when a filename is computed for the control panel. The two must agree exactly, or a mock is
 * written under a name no request can resolve to and nothing reports it. These fix the rule so a
 * later change to either caller has to state its intent here first.
 */
class NormaliserTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(Normaliser.normalise("  42  ")).contains("42");
    }

    /**
     * Empty is absent, not a key with an empty value. Otherwise a parameter sent as {@code
     * ?limit=} would resolve to a different file than one omitted altogether, which is not a
     * distinction any caller means to draw.
     */
    @Test
    void treatsEmptyAndWhitespaceOnlyAsAbsent() {
        assertThat(Normaliser.normalise("")).isEmpty();
        assertThat(Normaliser.normalise("   ")).isEmpty();
        assertThat(Normaliser.normalise(null)).isEmpty();
    }

    /**
     * The same identifier is zero-padded by some upstreams and not by others. Both spellings have
     * to reach the one mock, or every caller needs a duplicate file.
     */
    @Test
    void stripsLeadingZerosFromPurelyNumericValues() {
        assertThat(Normaliser.normalise("00005678")).contains("5678");
        assertThat(Normaliser.normalise("5678")).contains("5678");
    }

    /** A value that is entirely zeros is still a value; stripping it away would make it absent. */
    @Test
    void keepsASingleZero() {
        assertThat(Normaliser.normalise("0")).contains("0");
        assertThat(Normaliser.normalise("0000")).contains("0");
    }

    /**
     * Only purely numeric values are stripped. An identifier whose zeros are part of its shape —
     * a reference code, a padded segment inside a longer string — must survive intact.
     */
    @Test
    void leavesValuesThatAreNotAllDigitsAlone() {
        assertThat(Normaliser.normalise("AC-0100")).contains("AC-0100");
        assertThat(Normaliser.normalise("0x1F")).contains("0x1F");
        assertThat(Normaliser.normalise("007-alpha")).contains("007-alpha");
    }

    /**
     * Case is not folded here. It is folded when the filename is built, which is the only place it
     * matters — see {@code MockQuery#keySignature}. Folding twice would be harmless; folding here
     * and not there would not be, so the boundary is worth pinning.
     */
    @Test
    void doesNotChangeCase() {
        assertThat(Normaliser.normalise("MixedCase")).contains("MixedCase");
    }
}
