package com.tao.sandbox.control;

import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockMeta;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/**
 * The optimistic-concurrency protocol for mocks: content ETags, and the {@code If-Match} rules
 * that keep two dashboard tabs against one mounted share from overwriting each other silently.
 */
final class MockEtags {

    private MockEtags() {}

    /**
     * A content hash, over the payload and everything the sidecars contribute.
     *
     * <p>Content, not the timestamp: a mounted share's clock is not this machine's, and a
     * second-resolution mtime cannot tell two edits inside one second apart.
     *
     * <p>Headers are sorted rather than taken in iteration order: {@link MockMeta} holds them in an
     * immutable map whose ordering is randomised per JVM, so an unsorted digest would change on
     * every restart and make every held ETag stale.
     */
    static String etag(MockDocument document) {
        MockMeta meta = document.meta();

        StringBuilder material =
                new StringBuilder()
                        .append(document.body())
                        .append(' ')
                        .append(document.envelopeHeader())
                        .append(' ')
                        .append(meta.status())
                        .append(' ')
                        .append(meta.contentType())
                        .append(' ')
                        .append(meta.kind());

        meta.headers().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        header ->
                                material
                                        .append(' ')
                                        .append(header.getKey())
                                        .append('=')
                                        .append(header.getValue()));

        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * {@code If-Match} is required whenever the mock already exists; on one that does not, a
     * supplied header means the caller believed it existed, and that belief is now wrong — which
     * is exactly the stale-state case the header is for.
     *
     * @param existing null when the mock does not exist yet
     * @throws ControlPanelProblem 428 when the header is needed and absent, 412 when it is stale
     */
    static void requireFreshness(MockId id, MockDocument existing, String ifMatch) {
        String supplied = ifMatch == null || ifMatch.isBlank() ? null : ifMatch.trim();

        if (existing == null) {
            if (supplied != null && !supplied.equals("*")) {
                throw ControlPanelProblem.preconditionFailed(
                        "stale-mock",
                        "Mock has changed",
                        "%s no longer exists — it was deleted after you loaded it.".formatted(id.asPath()));
            }
            return;
        }

        if (supplied == null) {
            throw ControlPanelProblem.preconditionRequired(
                    "if-match-required",
                    "If-Match required",
                    "%s already exists. Send the ETag you read it with, so a concurrent edit is refused "
                                    .formatted(id.asPath())
                            + "rather than silently overwritten.");
        }

        String current = quoted(etag(existing));
        boolean matches =
                supplied.equals("*")
                        || Arrays.stream(supplied.split(","))
                                .map(String::trim)
                                .map(MockEtags::stripWeak)
                                .anyMatch(candidate -> candidate.equals(current));

        if (!matches) {
            throw ControlPanelProblem.preconditionFailed(
                    "stale-mock",
                    "Mock has changed",
                    "%s has been modified since you loaded it. Re-read it and reapply your change."
                            .formatted(id.asPath()));
        }
    }

    private static String quoted(String etag) {
        return etag.startsWith("\"") ? etag : "\"" + etag + "\"";
    }

    private static String stripWeak(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }
}
