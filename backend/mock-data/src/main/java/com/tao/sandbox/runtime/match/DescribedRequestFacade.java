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

    @Override
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>(pathVariables.keySet());
        names.addAll(query.keySet());
        if (body != null && body.isObject()) {
            // Top level only: a key deeper than that is addressed by an expression, not a name, and
            // listing every leaf of a large payload would bury the ones that matter.
            body.propertyNames().forEach(names::add);
        }
        return names;
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
