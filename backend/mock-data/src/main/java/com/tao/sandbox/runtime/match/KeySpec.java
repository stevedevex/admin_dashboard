package com.tao.sandbox.runtime.match;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One declared identity field.
 *
 * <p>Declared as {@code source:expression}, e.g. {@code path:petId}, {@code query:limit},
 * {@code body:$.accountId}, {@code xpath:/soapenv:Envelope/soapenv:Body/sq:Request/tickerSymbol}.
 *
 * <p>Source and strategy are orthogonal: a GET reading query parameters and a POST reading a body
 * can both be "all keys required" or "first key present wins".
 *
 * <h2>Naming a key something shorter</h2>
 *
 * <p>A declaration may end {@code … as <alias>}, and then the alias is what appears in filenames
 * and traces:
 *
 * <pre>
 *   xpath:/soapenv:Envelope/soapenv:Body/b:Request/b:BusinessRelationId as brid
 * </pre>
 *
 * <p>Real element names are the default, because they need no configuration and a filename that
 * uses them explains itself. But a schema written by a system that spells things out — {@code
 * BusinessRelationId}, {@code CounterpartyLegalEntityId} — produces names long enough to matter:
 * not usually against the 255-byte limit on one filename, but against the total path length on a
 * Windows checkout of the mock store, and against a reader's patience in a directory listing.
 *
 * <p>So the short form is opt-in and stated, never derived. An abbreviation nobody chose sitting
 * between "the name a mock was saved under" and "the name resolution computes" would be one
 * refactor away from making every existing file unreachable, silently.
 */
public record KeySpec(String name, Source source, String expression) {

    /** What an alias may be: it becomes part of a filename, so it names one field and nothing else. */
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String AS = " as ";

    public enum Source {
        PATH,
        QUERY,
        HEADER,
        BODY,
        XPATH
    }

    public static KeySpec parse(String declaration) {
        if (declaration == null || declaration.isBlank()) {
            throw new IllegalArgumentException("Key declaration is empty");
        }

        // Split the alias off first: left in place, it would be read as part of the expression and
        // silently produce a key that reads nothing.
        String alias = null;
        String remaining = declaration;

        int as = remaining.lastIndexOf(AS);
        if (as >= 0) {
            alias = remaining.substring(as + AS.length()).trim();
            remaining = remaining.substring(0, as);

            if (!ALIAS.matcher(alias).matches()) {
                // Refused rather than treated as part of the expression. Guessing which was meant
                // would make one of the two a silent mistake, and the silent one is a key that
                // never matches.
                throw new IllegalArgumentException(
                        "'%s' is not a usable key name — letters, digits, hyphen and underscore only. From: %s"
                                .formatted(alias, declaration));
            }
        }

        return parseDeclaration(remaining, alias, declaration);
    }

    private static KeySpec parseDeclaration(String remaining, String alias, String declaration) {
        int colon = remaining.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException(
                    "Key must be declared as source:expression, e.g. path:petId — got: " + declaration);
        }

        String rawSource = remaining.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        String expression = remaining.substring(colon + 1).trim();
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("Key expression is empty: " + declaration);
        }

        Source source;
        try {
            source = Source.valueOf(rawSource);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown key source '%s'. Expected one of %s"
                            .formatted(rawSource, Arrays.toString(Source.values())));
        }

        return new KeySpec(alias != null ? alias : deriveName(source, expression), source, expression);
    }

    /**
     * Parses a whole operation's keys, reporting rather than throwing.
     *
     * <p>Shared by both loaders, which ran identical loops. Startup reports every fault at once, so
     * one unusable declaration must not hide the next.
     *
     * @param context what to name in a problem, e.g. {@code orders/getOrder}
     */
    public static List<KeySpec> parseAll(List<String> declarations, String context, List<String> problems) {
        List<KeySpec> keys = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();

        for (String declaration : declarations) {
            KeySpec key;
            try {
                key = parse(declaration);
            } catch (IllegalArgumentException e) {
                problems.add("%s: %s".formatted(context, e.getMessage()));
                continue;
            }

            // Compared case-insensitively because a filename is lowercased: two keys named
            // 'brid' and 'BRID' are one key by the time they reach a file, and the mock written
            // for the second would overwrite the first.
            if (!taken.add(key.name().toLowerCase(Locale.ROOT))) {
                problems.add(
                        "%s: two keys are both called '%s'. Names become the key= part of a filename, so they must differ — give one an alias with 'as'."
                                .formatted(context, key.name()));
                continue;
            }

            keys.add(key);
        }

        return keys;
    }

    /**
     * Whether a name a caller used means this key.
     *
     * <p>Four spellings mean the same field: the key's name, the name it would have had without an
     * alias, its raw expression, and its whole declaration. {@code GET /__tao/services} reports
     * several of them, so a caller holding any one is holding this key.
     *
     * <p>Asked in two places, and they must agree. The control panel uses it to match supplied key
     * values against what an operation declares; the dry run uses it to decide which of the fields
     * a request carried were read and which were ignored. Answering differently would either lose a
     * caller's value or tell them a field was discarded when it decided the answer.
     */
    public boolean matchesName(String candidate) {
        return candidate != null
                && (candidate.equalsIgnoreCase(name)
                        || candidate.equalsIgnoreCase(derivedName())
                        || candidate.equalsIgnoreCase(expression)
                        || candidate.equalsIgnoreCase(source + ":" + expression));
    }

    /**
     * The name this key would have without an alias — the leaf of its expression.
     *
     * <p>Kept reachable because the control panel accepts a key under any spelling that means it,
     * and an aliased key has one more: whoever is writing a request by hand may well use the name
     * the schema uses. See {@code MockNaming}.
     */
    public String derivedName() {
        return deriveName(source, expression);
    }

    /**
     * Whether this key reads the field at {@code path}, or reads something inside it.
     *
     * <p>A different question from {@link #matchesName}, which asks whether a string is one of the
     * spellings of this key. This one is asked of a field a request actually carried, to decide
     * whether extraction looked at it — and the answer has to account for depth, because a key
     * reaching {@code $.customer.id} reads the {@code customer} object too. Asking {@code
     * matchesName} instead reported {@code customer} as ignored, which is the worst answer available:
     * the reader is consulting the list precisely because they already believe the wrong thing about
     * which fields matter, and being told the container is ignored confirms it.
     *
     * <p>Either direction counts as read. A field shallower than the key is something the key reads
     * into; a field deeper than it sits inside a value the key selects whole. Only a genuine
     * divergence — {@code customer.name} against {@code customer.id} — is ignored, which is exactly
     * the case worth reporting.
     */
    public boolean reads(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        List<String> mine = fieldPath();
        List<String> theirs = segmentsOf(path, '.');

        for (int i = 0; i < Math.min(mine.size(), theirs.size()); i++) {
            if (!mine.get(i).equalsIgnoreCase(theirs.get(i))) {
                return false;
            }
        }
        return !mine.isEmpty() && !theirs.isEmpty();
    }

    /**
     * Where this key reads, as the path segments a request facade enumerates.
     *
     * <p>The two vocabularies have to meet somewhere, and this is the seam. A JSON key drops its
     * {@code $.} root and its array indices; an XPath key drops the envelope scaffolding, because a
     * facade enumerating an envelope's fields starts below it — at the operation element for a body
     * field, and at the header's own children for a header one. What remains on both sides is the
     * path from there down.
     *
     * <p>Array indices are dropped rather than kept because a facade does not descend into arrays: a
     * key must select exactly one value, so identity is never inside a list, and enumerating one
     * would produce an entry per element for nothing.
     */
    public List<String> fieldPath() {
        return switch (source) {
            case PATH, QUERY, HEADER -> List.of(expression);
            case BODY -> segmentsOf(expression.replaceFirst("^\\$\\.?", ""), '.');
            case XPATH -> belowEnvelope(segmentsOf(expression, '/'));
        };
    }

    /**
     * Everything after the envelope section a facade starts from.
     *
     * <p>{@code Body} is followed by the operation element, which is not a field — the fields are its
     * children — so two segments go. {@code Header} carries its fields directly, so one does. An
     * expression shaped like neither is left whole rather than guessed at: it will simply not match,
     * which is the same answer this gave before any of this existed.
     */
    private static List<String> belowEnvelope(List<String> segments) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).equalsIgnoreCase("Body")) {
                return i + 2 <= segments.size() ? segments.subList(i + 2, segments.size()) : List.of();
            }
            if (segments.get(i).equalsIgnoreCase("Header")) {
                return segments.subList(i + 1, segments.size());
            }
        }
        return segments;
    }

    /** Split, with the attribute marker, namespace prefixes and array indices taken off each part. */
    private static List<String> segmentsOf(String expression, char separator) {
        List<String> segments = new ArrayList<>();

        for (String raw : expression.split(Pattern.quote(String.valueOf(separator)))) {
            String segment = raw.trim().replace("@", "").replaceAll("\\[[^]]*]", "");
            int prefix = segment.indexOf(':');
            if (prefix >= 0) {
                segment = segment.substring(prefix + 1);
            }
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }

        return segments;
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
