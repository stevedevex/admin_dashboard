package com.tao.sandbox.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockMeta;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The optimistic-concurrency rules for mocks.
 *
 * <p>What these protect is invisible when they work: two dashboard tabs against one mounted share,
 * where a lost edit leaves no trace and the author who lost it has no way to discover that they
 * did. The failure mode has no symptom, so the rules are pinned rather than trusted.
 */
class MockEtagsTest {

    private static final MockId ID = new MockId("base", "svc", "op", "id=1.json");

    // --- the tag itself -----------------------------------------------------

    @Test
    void identicalContentTagsIdentically() {
        assertThat(MockEtags.etag(document("body"))).isEqualTo(MockEtags.etag(document("body")));
    }

    @Test
    void aChangedPayloadChangesTheTag() {
        assertThat(MockEtags.etag(document("body"))).isNotEqualTo(MockEtags.etag(document("other")));
    }

    /**
     * The tag covers the sidecars too. A mock whose payload is untouched but whose status moved
     * from 200 to 503 is a different response, and an unchanged tag would let a concurrent edit
     * overwrite that change silently.
     */
    @Test
    void aChangedSidecarChangesTheTag() {
        MockDocument plain = document("body");
        MockDocument withStatus =
                new MockDocument("body", null, new MockMeta(503, null, Map.of(), null));

        assertThat(MockEtags.etag(plain)).isNotEqualTo(MockEtags.etag(withStatus));
    }

    /**
     * Headers are digested in sorted order. {@link MockMeta} holds them immutably, and an
     * iteration-order digest would change between runs and make every held ETag stale on restart.
     */
    @Test
    void theOrderHeadersWereSuppliedInDoesNotChangeTheTag() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("X-A", "1");
        one.put("X-B", "2");

        Map<String, String> other = new LinkedHashMap<>();
        other.put("X-B", "2");
        other.put("X-A", "1");

        assertThat(MockEtags.etag(new MockDocument("body", null, new MockMeta(null, null, one, null))))
                .isEqualTo(
                        MockEtags.etag(new MockDocument("body", null, new MockMeta(null, null, other, null))));
    }

    // --- If-Match on a mock that exists -------------------------------------

    /**
     * Blind writes are refused rather than accepted. Without this, the second of two tabs wins and
     * nothing anywhere records that the first one lost.
     */
    @Test
    void savingOverAnExistingMockWithoutAPreconditionIsRefused() {
        assertThat(refusalFrom(() -> MockEtags.requireFreshness(ID, document("stored"), null)))
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void aBlankPreconditionCountsAsNoneAtAll() {
        assertThat(refusalFrom(() -> MockEtags.requireFreshness(ID, document("stored"), "   ")))
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void theCurrentTagIsAccepted() {
        MockDocument stored = document("stored");

        MockEtags.requireFreshness(ID, stored, "\"" + MockEtags.etag(stored) + "\"");
    }

    @Test
    void aTagFromAnEarlierVersionIsRefused() {
        assertThat(
                        refusalFrom(
                                () ->
                                        MockEtags.requireFreshness(
                                                ID,
                                                document("stored"),
                                                "\"" + MockEtags.etag(document("earlier")) + "\"")))
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    /** A proxy may weaken a tag in transit; the comparison has to see through that. */
    @Test
    void aWeakenedTagIsStillTheSameTag() {
        MockDocument stored = document("stored");

        MockEtags.requireFreshness(ID, stored, "W/\"" + MockEtags.etag(stored) + "\"");
    }

    @Test
    void anyTagInASuppliedListMayMatch() {
        MockDocument stored = document("stored");

        MockEtags.requireFreshness(
                ID, stored, "\"" + MockEtags.etag(document("earlier")) + "\", \"" + MockEtags.etag(stored) + "\"");
    }

    @Test
    void aWildcardMeansAnyCurrentVersionWillDo() {
        MockEtags.requireFreshness(ID, document("stored"), "*");
    }

    // --- If-Match on a mock that does not exist yet -------------------------

    /** There is no version to match, so creating one needs no precondition. */
    @Test
    void creatingAMockNeedsNoPrecondition() {
        MockEtags.requireFreshness(ID, null, null);
        MockEtags.requireFreshness(ID, null, "*");
    }

    /**
     * A concrete tag on a mock that does not exist means the caller believed it did — which is
     * exactly the stale-state case the header is for, so it is answered rather than ignored.
     */
    @Test
    void aConcreteTagOnAMockThatIsGoneIsRefused() {
        assertThat(refusalFrom(() -> MockEtags.requireFreshness(ID, null, "\"whatever-they-held\"")))
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    // --- helpers ------------------------------------------------------------

    private static MockDocument document(String body) {
        return new MockDocument(body, null, MockMeta.none());
    }

    /** @return the status the write was refused with, failing the test if it was allowed through */
    private static HttpStatus refusalFrom(Runnable call) {
        try {
            call.run();
        } catch (ControlPanelProblem problem) {
            return HttpStatus.valueOf(problem.asProblemDetail().getStatus());
        }
        throw new AssertionError("Expected the write to be refused, but it was allowed");
    }
}
