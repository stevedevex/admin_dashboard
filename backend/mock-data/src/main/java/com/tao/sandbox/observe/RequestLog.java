package com.tao.sandbox.observe;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.runtime.resolve.ResolutionTrace;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedMap;
import org.springframework.stereotype.Component;

/**
 * What was actually served, and to whom.
 *
 * <p>Bounded, in memory, dropped on restart. Persisting it would make the sandbox a system with
 * state worth backing up, which is the opposite of what it is for.
 *
 * <p>The log is read by polling {@code ?since=}, not pushed. Two seconds of staleness is invisible
 * in a development tool, and polling survives every corporate proxy that would quietly break an
 * SSE stream — a debugging aid that itself needs debugging is worth less than nothing.
 */
@Component
public class RequestLog {

    /**
     * Who made the call, declared by the caller in this header.
     *
     * <p>Not configurable, unlike the scenario override: this is an internal agreement between the
     * playground and the log, not something a deployment has reason to rename.
     */
    public static final String SOURCE_HEADER = "X-Sandbox-Source";

    /**
     * The id of the entry a response was logged under, echoed on every served response.
     *
     * <p>Server truth about a call rather than part of what a mock describes, so it is set after a
     * mock's own headers and cannot be overridden by one.
     */
    public static final String REQUEST_ID_HEADER = "X-Sandbox-Request-Id";

    /**
     * Whether a call came from the application under test or from someone trying one by hand.
     *
     * <p>Recorded rather than filtered out, because the alternatives are both worse. Dropping
     * hand-made calls would leave the log disagreeing with what the server demonstrably served, and
     * mixing them in unlabelled would have the log answer "did my application send that?" wrongly —
     * which is the one question it exists to answer.
     */
    public enum Source {
        CLIENT,
        PLAYGROUND;

        /** Anything unrecognised is a client: the header is a claim, and the default is the truth. */
        public static Source of(String header) {
            if (header == null) {
                return CLIENT;
            }
            try {
                return valueOf(header.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CLIENT;
            }
        }
    }

    /**
     * @param id monotonic and never reused, so it works as a cursor
     * @param matched the mock that answered, or null on a miss — the misses are what the log is
     *     usually opened for
     * @param bodiesTruncated set when a body was longer than the retained limit, so nobody reads a
     *     cut-off payload as the whole one
     */
    public record Entry(
            long id,
            Instant at,
            Source source,
            String serviceId,
            String operationId,
            String scenarioId,
            int status,
            long tookMillis,
            String matched,
            boolean inherited,
            SequencedMap<String, String> extracted,
            List<String> attempted,
            String requestBody,
            String responseBody,
            boolean bodiesTruncated) {}

    /**
     * @param mode {@code FULL} when every request since the caller's cursor is present, {@code
     *     SAMPLED} when the buffer wrapped and some were lost. It must be said out loud: a silently
     *     truncating log is worse than none, because quiet reads as "no traffic".
     */
    public record Page(String mode, String cursor, List<Entry> entries) {}

    private final int capacity;
    private final int maxBodyChars;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextId = 1;
    /** The id of the oldest entry still retained, so a cursor older than it can be reported lost. */
    private long oldestRetained = 1;

    public RequestLog(SandboxProperties properties) {
        this.capacity = Math.max(1, properties.requestLog().capacity());
        this.maxBodyChars = Math.max(0, properties.requestLog().maxBodyChars());
    }

    /**
     * One resolved request, hit or miss.
     *
     * @return the id of the entry just written, so the caller can name it in a response header. A
     *     client holding the id of its own call can ask what the server decided without hunting for
     *     it in a log that other traffic is also arriving in.
     */
    public synchronized long record(
            ResolutionTrace trace, int status, String requestBody, String responseBody, Source source) {

        String request = truncate(requestBody);
        String response = truncate(responseBody);

        return append(
                new Entry(
                        nextId,
                        Instant.now(),
                        source,
                        trace.serviceId(),
                        trace.operationId(),
                        trace.scenarioId(),
                        status,
                        trace.took().toMillis(),
                        trace.matched() == null ? null : trace.matched().asPath(),
                        trace.inherited(),
                        new LinkedHashMap<>(trace.extracted()),
                        trace.attempted(),
                        request,
                        response,
                        truncated(requestBody, request) || truncated(responseBody, response)));
    }

    /**
     * A request rejected before resolution — a malformed envelope, an operation the contract
     * declares but configuration does not serve.
     *
     * <p>Recorded rather than dropped: a client sending nothing the sandbox understands sees an
     * empty log otherwise, which reads as "my requests are not arriving" and sends whoever is
     * debugging it to the network layer.
     */
    public synchronized long recordRejected(
            String serviceId, String reason, int status, String requestBody, Source source) {

        String request = truncate(requestBody);

        return append(
                new Entry(
                        nextId,
                        Instant.now(),
                        source,
                        serviceId,
                        null,
                        null,
                        status,
                        0,
                        null,
                        false,
                        new LinkedHashMap<>(),
                        List.of(),
                        request,
                        reason,
                        truncated(requestBody, request)));
    }

    /** Entries after the given cursor, oldest first. */
    public synchronized Page since(String cursor, int limit) {
        long after = parse(cursor);

        List<Entry> selected = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id() > after && selected.size() < Math.max(1, limit)) {
                selected.add(entry);
            }
        }

        // The caller asked to continue from an entry that has already been evicted, so what they
        // get is a sample of what happened, not all of it.
        boolean lost = cursor != null && after > 0 && after + 1 < oldestRetained;

        String next =
                selected.isEmpty()
                        ? String.valueOf(nextId - 1)
                        : String.valueOf(selected.getLast().id());

        return new Page(lost ? "SAMPLED" : "FULL", next, selected);
    }

    public synchronized Optional<Entry> find(long id) {
        return entries.stream().filter(entry -> entry.id() == id).findFirst();
    }

    public synchronized void clear() {
        entries.clear();
    }

    // --- internals ---------------------------------------------------------

    private long append(Entry entry) {
        entries.addLast(entry);
        nextId++;

        while (entries.size() > capacity) {
            Entry evicted = entries.removeFirst();
            oldestRetained = evicted.id() + 1;
        }

        return entry.id();
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= maxBodyChars ? body : body.substring(0, maxBodyChars);
    }

    private boolean truncated(String original, String retained) {
        return original != null && retained != null && original.length() != retained.length();
    }

    private long parse(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cursor must be an entry id, got: " + cursor);
        }
    }
}
