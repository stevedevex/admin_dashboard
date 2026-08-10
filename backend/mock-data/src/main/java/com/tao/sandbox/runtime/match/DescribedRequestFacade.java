package com.tao.sandbox.runtime.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A request described rather than received, for the dry run.
 *
 * <p>Exists so {@code POST /__tao/resolve} runs the real pipeline instead of a reimplementation of
 * it. A dry run that answered from its own copy of the matching rules would agree with the server
 * right up until the moment the two drifted — which is precisely when someone would be using it to
 * find out why a request did not match.
 */
public class DescribedRequestFacade implements RequestFacade {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Map<String, String> pathVariables;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final String raw;
    private final JsonNode body;

    public DescribedRequestFacade(
            Map<String, String> pathVariables,
            Map<String, String> query,
            Map<String, String> headers,
            String body) {

        this.pathVariables = copy(pathVariables);
        this.query = copy(query);
        // Header lookup is case-insensitive on the wire, and someone pasting a request into the
        // dashboard will not match the casing the configuration happens to use.
        this.headers = new LinkedHashMap<>();
        copy(headers).forEach((name, value) -> this.headers.put(name.toLowerCase(Locale.ROOT), value));

        this.raw = body;
        this.body = parse(body);
    }

    @Override
    public Optional<String> path(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }

    @Override
    public Optional<String> query(String name) {
        return Optional.ofNullable(query.get(name));
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<String> body(String expression) {
        if (body == null) {
            return Optional.empty();
        }
        JsonNode node = body.at(JsonPointers.forExpression(expression));
        return node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node.asString());
    }

    @Override
    public Optional<String> xpath(String expression) {
        // XML arrives on the SOAP path, which supplies its own facade.
        return Optional.empty();
    }

    /**
     * Every field the request carries, nested ones as dotted paths.
     *
     * <p>This listed top-level names only, on the reasoning that deeper fields are addressed by an
     * expression rather than a name. That was wrong in a way that mattered: a key reading {@code
     * $.customer.id} left {@code customer} as the only name available, and {@code customer} is not
     * the name of any key — so the one field that decided the answer was reported as ignored. Depth
     * is now carried on both sides and compared by {@link KeySpec#reads}.
     *
     * <p>Arrays are leaves. A key must select exactly one value, so identity is never inside a list,
     * and descending would add an entry per element to say nothing.
     */
    @Override
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>(pathVariables.keySet());
        names.addAll(query.keySet());
        if (body != null && body.isObject()) {
            collect(body, "", names, 0);
        }
        return names;
    }

    /** The same bound the schema walkers use: far past any payload a person reads on screen. */
    private static final int MAX_DEPTH = 10;

    private void collect(JsonNode node, String prefix, List<String> names, int depth) {
        for (String property : node.propertyNames()) {
            String path = prefix.isEmpty() ? property : prefix + "." + property;
            JsonNode value = node.get(property);

            // An empty object is a leaf: there is nothing inside it to name, and reporting the
            // container is what tells a reader it arrived at all.
            if (value != null && value.isObject() && !value.isEmpty() && depth < MAX_DEPTH) {
                collect(value, path, names, depth + 1);
            } else {
                names.add(path);
            }
        }
    }

    /** The body as supplied, for the trace. */
    public String rawBody() {
        return raw;
    }

    private static Map<String, String> copy(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            // Not JSON, or malformed. Both are misses rather than failures — the trace showing no
            // keys extracted says more than a parser error would.
            return null;
        }
    }
}
