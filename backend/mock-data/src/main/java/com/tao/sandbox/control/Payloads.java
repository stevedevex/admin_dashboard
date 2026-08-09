package com.tao.sandbox.control;

import java.util.Locale;

/**
 * The two directions between a payload's media type and its file extension.
 *
 * <p>Both live here because they must agree. The extension is not part of resolution — any sibling
 * with the right stem is the match — so a mock saved as {@code .txt} when the dashboard shows it as
 * JSON still serves, and the disagreement surfaces only as an editor that will not highlight.
 */
final class Payloads {

    private Payloads() {}

    /** How the dashboard should render a stored file, from its extension. */
    static String formatOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

        return switch (extension) {
            case "json" -> "json";
            case "xml" -> "xml";
            default -> "text";
        };
    }

    /**
     * The extension a new mock should be saved under, from the media type its contract declares.
     *
     * <p>Matched loosely on purpose: {@code application/problem+json} and {@code
     * application/vnd.acme.pet.v2+json} are both JSON, and a suffix table would have to grow a new
     * row for every vendor type someone drops a spec for.
     */
    static String extensionFor(String contentType) {
        if (contentType == null) {
            return "txt";
        }

        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("json")) {
            return "json";
        }
        if (type.contains("xml")) {
            return "xml";
        }
        return "txt";
    }
}
