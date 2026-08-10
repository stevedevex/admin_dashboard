package com.tao.sandbox.store;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The key part of a mock's filename, written and read back.
 *
 * <p>Both directions live here because they are one rule seen twice. Writing turns extracted keys
 * into {@code brid=1001&bc=ch100}; reading turns a filename found on disk back into the keys it
 * claims to match on. Resolution needs the first, and anything that asks <em>which requests could
 * this file ever answer</em> needs the second.
 *
 * <p>Lowercased, because macOS and SMB shares are case-insensitive while Linux CI is not: a mock
 * authored on a laptop must not vanish in a container.
 *
 * <h2>Values are not escaped</h2>
 *
 * <p>A value containing {@code =} or {@code &} would produce a name that reads back as different
 * keys than it was written from. Escaping would be the thorough fix and would rename every file
 * that already exists, so instead such a value is <em>refused at authoring time</em> — see {@link
 * #problemWith} — where the message can say so.
 *
 * <p>Deliberately not refused here. This runs on the resolve path, where throwing would turn an
 * odd request value into a failed response rather than a miss. A request carrying one simply
 * matches nothing, which is the truth: no file could have been written under that name.
 */
public final class MockStem {

    /**
     * The longest a filename may be, in bytes. 255 is the per-component limit on ext4 and APFS.
     *
     * <p>Not the limit that bites first, though: a mock store is meant to be checked out, and a
     * Windows checkout is capped at 260 characters for the <em>whole</em> path — which the
     * scenario, service and operation directories have already spent a large part of. Hence the
     * softer warning below.
     */
    public static final int MAX_BYTES = 255;

    /** Past this, a name is fine here and a liability on a Windows checkout of the same store. */
    public static final int LONG_ENOUGH_TO_WARN = 120;

    private MockStem() {}

    /** The key part of a filename, or empty when there are no keys — which names the default. */
    public static String of(SequencedMap<String, String> keys) {
        if (keys.isEmpty()) {
            return "";
        }

        StringBuilder joined = new StringBuilder();
        keys.forEach(
                (key, value) -> {
                    if (!joined.isEmpty()) {
                        joined.append('&');
                    }
                    joined.append(key).append('=').append(value);
                });

        return joined.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The keys a stored filename says it matches on, in the order it wrote them.
     *
     * @return empty when the stem is not {@code key=value} shaped at all — which makes it a file
     *     no request can produce a name for, and so something a reader should be told about rather
     *     than a case to guess at
     */
    public static Optional<SequencedMap<String, String>> parse(String stem) {
        if (stem == null || stem.isBlank()) {
            return Optional.empty();
        }

        // The fallback is a name with no keys rather than a special case: it matches every request
        // for its operation, which is exactly what zero required pairs means.
        if (stem.equals(MockRepository.DEFAULT_STEM)) {
            return Optional.of(new LinkedHashMap<>());
        }

        SequencedMap<String, String> keys = new LinkedHashMap<>();
        for (String pair : stem.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 1 || equals == pair.length() - 1) {
                return Optional.empty();
            }
            keys.put(pair.substring(0, equals), pair.substring(equals + 1));
        }

        return Optional.of(keys);
    }

    /**
     * Why these keys cannot be written to a filename, if they cannot.
     *
     * <p>Asked before a mock is saved, never during resolution.
     */
    public static Optional<String> problemWith(Map<String, String> keys) {
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String offending = separatorIn(entry.getKey()) != null ? entry.getKey() : entry.getValue();
            String separator = separatorIn(offending);

            if (separator != null) {
                return Optional.of(
                        "'%s' contains '%s', which separates one key from the next in a file name. A mock named from it would be read back as different keys than it was written from."
                                .formatted(offending, separator));
            }
        }
        return Optional.empty();
    }

    /** Why this filename is unusable on the store, if it is. */
    public static Optional<String> problemWithLength(String fileName) {
        int bytes = fileName.getBytes(StandardCharsets.UTF_8).length;

        return bytes > MAX_BYTES
                ? Optional.of(
                        "the name is %d bytes and the limit is %d. Shorten it by giving its keys aliases — 'xpath:… as brid' — or by naming fewer of them."
                                .formatted(bytes, MAX_BYTES))
                : Optional.empty();
    }

    private static String separatorIn(String value) {
        if (value == null) {
            return null;
        }
        if (value.contains("&")) {
            return "&";
        }
        return value.contains("=") ? "=" : null;
    }
}
