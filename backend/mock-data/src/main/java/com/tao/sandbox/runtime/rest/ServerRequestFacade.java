package com.tao.sandbox.runtime.rest;

import com.tao.sandbox.runtime.match.RequestFacade;
import java.util.Optional;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a REST request for key extraction.
 *
 * <p>Uses its own JSON reader rather than the application's {@code ObjectMapper}. Extraction needs
 * to read a value at a path; it has no interest in the app's serialisation settings, and coupling
 * to them would break the moment someone customises them. Note that Spring Boot 4 ships Jackson 3
 * ({@code tools.jackson}) while several libraries still bring Jackson 2 ({@code
 * com.fasterxml.jackson}) transitively — being explicit here avoids picking up whichever happens
 * to win.
 *
 * <p>The body is read at most once, and only when a {@code body:} key is declared, so a
 * GET-shaped operation never touches it.
 */
public class ServerRequestFacade implements RequestFacade {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ServerRequest request;

    private boolean bodyRead;
    private JsonNode body;

    public ServerRequestFacade(ServerRequest request) {
        this.request = request;
    }

    @Override
    public Optional<String> path(String name) {
        return Optional.ofNullable(request.pathVariables().get(name));
    }

    @Override
    public Optional<String> query(String name) {
        return request.param(name);
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(request.headers().firstHeader(name));
    }

    /**
     * Supports the dotted subset of JSONPath — {@code $.a.b} and {@code $.a[0].b} — by mapping it
     * onto a JSON Pointer. That covers identity fields, which is all extraction needs: filters and
     * wildcards select sets, and a key must select exactly one value.
     */
    @Override
    public Optional<String> body(String expression) {
        JsonNode root = readBody();
        if (root == null) {
            return Optional.empty();
        }

        JsonNode node = root.at(toPointer(expression));
        return node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node.asString());
    }

    @Override
    public Optional<String> xpath(String expression) {
        // XML payloads arrive on the SOAP path, which supplies its own facade.
        return Optional.empty();
    }

    private JsonNode readBody() {
        if (!bodyRead) {
            bodyRead = true;
            try {
                String raw = request.body(String.class);
                body = raw == null || raw.isBlank() ? null : MAPPER.readTree(raw);
            } catch (Exception e) {
                // A malformed body is a miss, not a crash: the trace will show no keys were
                // extracted, which is more useful than a 500 with a parser stack trace.
                body = null;
            }
        }
        return body;
    }

    static String toPointer(String expression) {
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
