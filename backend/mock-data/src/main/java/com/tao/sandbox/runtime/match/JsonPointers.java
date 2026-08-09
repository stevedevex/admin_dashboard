package com.tao.sandbox.runtime.match;

/**
 * The dotted subset of JSONPath, expressed as a JSON Pointer.
 *
 * <p>{@code $.a.b} and {@code $.a[0].b} are supported and nothing else. That covers identity
 * fields, which is all extraction needs: filters and wildcards select sets, and a key must select
 * exactly one value or the mock it names is ambiguous.
 *
 * <p>Shared so the live request path and the dry run cannot interpret the same expression
 * differently — a dry run that reports a different key than the real request would extract is a
 * diagnostic that lies at exactly the moment someone is relying on it.
 */
public final class JsonPointers {

    private JsonPointers() {}

    public static String forExpression(String expression) {
        String path = expression.startsWith("$") ? expression.substring(1) : expression;
        path = path.replace("[", ".").replace("]", "");

        StringBuilder pointer = new StringBuilder();
        for (String segment : path.split("\\.")) {
            if (!segment.isBlank()) {
                pointer.append('/').append(segment);
            }
        }
        return pointer.toString();
    }
}
