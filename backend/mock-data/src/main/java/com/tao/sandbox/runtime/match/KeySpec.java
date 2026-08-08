package com.tao.sandbox.runtime.match;

import java.util.Locale;

/**
 * One declared identity field.
 *
 * <p>Declared as {@code source:expression}, e.g. {@code path:petId}, {@code query:limit},
 * {@code body:$.accountId}, {@code xpath:/soapenv:Envelope/soapenv:Body/sq:Request/tickerSymbol}.
 *
 * <p>Source and strategy are orthogonal: a GET reading query parameters and a POST reading a body
 * can both be "all keys required" or "first key present wins".
 */
public record KeySpec(String name, Source source, String expression) {

    public enum Source {
        PATH,
        QUERY,
        HEADER,
        BODY,
        XPATH
    }

    public static KeySpec parse(String declaration) {
        int colon = declaration.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException(
                    "Key must be declared as source:expression, e.g. path:petId — got: " + declaration);
        }

        String rawSource = declaration.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        String expression = declaration.substring(colon + 1).trim();
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("Key expression is empty: " + declaration);
        }

        Source source;
        try {
            source = Source.valueOf(rawSource);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown key source '%s'. Expected one of %s"
                            .formatted(rawSource, java.util.Arrays.toString(Source.values())));
        }

        return new KeySpec(deriveName(source, expression), source, expression);
    }

    /**
     * The name used in filenames and traces.
     *
     * <p>Taken from the leaf of the expression so that a long XPath still produces a short,
     * readable key — {@code …/sq:Request/tickerSymbol} becomes {@code tickerSymbol}.
     */
    private static String deriveName(Source source, String expression) {
        String leaf =
                switch (source) {
                    case PATH, QUERY, HEADER -> expression;
                    case BODY -> lastSegment(expression, '.');
                    case XPATH -> lastSegment(expression, '/');
                };

        // Strip XPath attribute and namespace markers: "@ns:id" -> "id"
        leaf = leaf.replace("@", "");
        int prefix = leaf.indexOf(':');
        return prefix >= 0 ? leaf.substring(prefix + 1) : leaf;
    }

    private static String lastSegment(String expression, char separator) {
        int index = expression.lastIndexOf(separator);
        return index >= 0 ? expression.substring(index + 1) : expression;
    }
}
