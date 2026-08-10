package com.tao.sandbox.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.config.SandboxProperties.StoreType;
import com.tao.sandbox.runtime.resolve.ResolutionTrace;
import com.tao.sandbox.store.MockId;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

/**
 * The bounded log, and the one thing it must never do quietly.
 *
 * <p>A log that drops entries without saying so is worse than no log: silence reads as "my
 * requests are not arriving", and sends whoever is debugging to the network layer. So the wrap is
 * reported, and truncation is flagged rather than hidden.
 */
class RequestLogTest {

    @Test
    void recordsWhatResolutionConcluded() {
        RequestLog log = log(10, 1000);
        log.record(hit(), 200, "the request", "the response");

        RequestLog.Entry entry = log.since(null, 10).entries().getFirst();

        assertThat(entry.serviceId()).isEqualTo("svc");
        assertThat(entry.operationId()).isEqualTo("op");
        assertThat(entry.status()).isEqualTo(200);
        assertThat(entry.matched()).isEqualTo("base/svc/op/id=1.json");
        assertThat(entry.extracted()).containsEntry("id", "1");
        assertThat(entry.attempted()).contains("scenarios/base/svc/op/id=1");
        assertThat(entry.bodiesTruncated()).isFalse();
    }

    /** A miss is the entry the log is usually opened for, so it carries the paths that were tried. */
    @Test
    void recordsAMissWithNothingMatched() {
        RequestLog log = log(10, 1000);
        log.record(miss(), 404, "the request", null);

        RequestLog.Entry entry = log.since(null, 10).entries().getFirst();

        assertThat(entry.matched()).isNull();
        assertThat(entry.attempted()).isNotEmpty();
    }

    /**
     * A client sending nothing the sandbox understands would otherwise see an empty log, which
     * reads as traffic not arriving at all.
     */
    @Test
    void recordsARequestRejectedBeforeItReachedAnOperation() {
        RequestLog log = log(10, 1000);
        log.recordRejected("svc", "Malformed SOAP request", 400, "<not-an-envelope/>");

        RequestLog.Entry entry = log.since(null, 10).entries().getFirst();

        assertThat(entry.serviceId()).isEqualTo("svc");
        assertThat(entry.operationId()).isNull();
        assertThat(entry.status()).isEqualTo(400);
        assertThat(entry.responseBody()).isEqualTo("Malformed SOAP request");
    }

    @Test
    void ordersEntriesOldestFirstAndHonoursTheLimit() {
        RequestLog log = log(10, 1000);
        record(log, 3);

        assertThat(log.since(null, 2).entries()).extracting(RequestLog.Entry::id).containsExactly(1L, 2L);
    }

    @Test
    void aCursorReturnsOnlyWhatFollowsIt() {
        RequestLog log = log(10, 1000);
        record(log, 3);

        assertThat(log.since("1", 10).entries()).extracting(RequestLog.Entry::id).containsExactly(2L, 3L);
    }

    /** Bounded, because an unbounded log of a service under load is a memory leak with a friendly name. */
    @Test
    void keepsOnlyTheMostRecentEntriesOnceFull() {
        RequestLog log = log(3, 1000);
        record(log, 5);

        assertThat(log.since(null, 10).entries()).extracting(RequestLog.Entry::id).containsExactly(3L, 4L, 5L);
    }

    /** Said out loud, never inferred: a silently thinned history presented as the whole one. */
    @Test
    void saysSoWhenTheBufferWrappedPastTheCallersCursor() {
        RequestLog log = log(3, 1000);
        record(log, 5);

        assertThat(log.since("1", 10).mode()).isEqualTo("SAMPLED");
    }

    @Test
    void reportsAFullHistoryWhenNothingWasLost() {
        RequestLog log = log(10, 1000);
        record(log, 3);

        assertThat(log.since("1", 10).mode()).isEqualTo("FULL");
        assertThat(log.since(null, 10).mode()).isEqualTo("FULL");
    }

    /** The cursor a caller gets back has to be usable as the next one, even with nothing to report. */
    @Test
    void handsBackACursorEvenWhenNothingIsNew() {
        RequestLog log = log(10, 1000);
        record(log, 2);

        assertThat(log.since(log.since(null, 10).cursor(), 10).entries()).isEmpty();
    }

    @Test
    void refusesACursorThatIsNotAnEntryId() {
        RequestLog log = log(10, 1000);

        assertThatIllegalArgumentException().isThrownBy(() -> log.since("not-a-number", 10));
    }

    /** Truncated, not dropped — a large payload still shows what it was — and flagged as such. */
    @Test
    void truncatesLongBodiesAndSaysThatItDid() {
        RequestLog log = log(10, 5);
        log.record(hit(), 200, "0123456789", "short");

        RequestLog.Entry entry = log.since(null, 10).entries().getFirst();

        assertThat(entry.requestBody()).isEqualTo("01234");
        assertThat(entry.bodiesTruncated()).isTrue();
    }

    @Test
    void findsAnEntryById() {
        RequestLog log = log(10, 1000);
        record(log, 2);

        assertThat(log.find(2).orElseThrow().id()).isEqualTo(2L);
        assertThat(log.find(99)).isEmpty();
    }

    @Test
    void anEntryThatHasAgedOutIsNoLongerFound() {
        RequestLog log = log(2, 1000);
        record(log, 4);

        assertThat(log.find(1)).isEmpty();
    }

    // --- fixtures -----------------------------------------------------------

    private static RequestLog log(int capacity, int maxBodyChars) {
        return new RequestLog(
                new SandboxProperties(
                        StoreType.FILESYSTEM,
                        null,
                        null,
                        new SandboxProperties.RequestLog(capacity, maxBodyChars),
                        null,
                        null));
    }

    private static void record(RequestLog log, int count) {
        for (int i = 0; i < count; i++) {
            log.record(hit(), 200, "request " + i, "response " + i);
        }
    }

    private static ResolutionTrace hit() {
        return trace(new MockId("base", "svc", "op", "id=1.json"));
    }

    private static ResolutionTrace miss() {
        return trace(null);
    }

    private static ResolutionTrace trace(MockId matched) {
        SequencedMap<String, String> extracted = new LinkedHashMap<>();
        extracted.put("id", "1");

        return new ResolutionTrace(
                "svc",
                "op",
                "base",
                extracted,
                List.of("scenarios/base/svc/op/id=1", "scenarios/base/svc/op/_default"),
                matched,
                false,
                Duration.ofMillis(2));
    }
}
