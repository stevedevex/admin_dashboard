package com.tao.sandbox.store.fs;

import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockMeta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Files that sit beside a payload and describe or extend it.
 *
 * <pre>
 *   tickersymbol=ibm.xml           the payload — valid against its own schema, nothing else in it
 *   tickersymbol=ibm.meta.yaml     status, HTTP headers, kind
 *   tickersymbol=ibm.header.xml    the SOAP envelope header
 * </pre>
 *
 * <p>The envelope header is a separate XML file rather than a field in the YAML for two reasons:
 * {@code headers:} in the meta sidecar already means <em>HTTP</em> headers, and putting an
 * envelope header next to it invites exactly the confusion the naming suggests; and XML kept as
 * XML stays editable, highlightable and validatable instead of becoming a YAML block scalar.
 */
final class Sidecars {

    static final String META = ".meta.yaml";
    static final String ENVELOPE_HEADER = ".header.xml";

    private static final List<String> ALL = List.of(META, ENVELOPE_HEADER);

    private Sidecars() {}

    static boolean isSidecar(String fileName) {
        return ALL.stream().anyMatch(fileName::endsWith);
    }

    /** Sidecar path for a payload, e.g. {@code petid=1.json} → {@code petid=1.meta.yaml}. */
    static Path pathFor(Path payload, String suffix) {
        String name = payload.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return payload.resolveSibling(stem + suffix);
    }

    static List<Path> allFor(Path payload) {
        return ALL.stream().map(suffix -> pathFor(payload, suffix)).toList();
    }

    /**
     * The envelope header for this mock, or null when it has none.
     *
     * <p>Null rather than empty on purpose: some stacks treat an empty {@code <Header/>} element
     * differently from an absent one, so "no header" must stay distinguishable from "an empty one".
     */
    static String readEnvelopeHeader(Path payload) {
        Path sidecar = pathFor(payload, ENVELOPE_HEADER);
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            String content = Files.readString(sidecar, StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + sidecar, e);
        }
    }

    static MockMeta readMeta(Path payload) {
        Path sidecar = pathFor(payload, META);
        if (!Files.isRegularFile(sidecar)) {
            return MockMeta.none();
        }

        Map<String, Object> raw;
        try {
            raw = new Yaml().load(Files.readString(sidecar, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + sidecar, e);
        }
        if (raw == null) {
            return MockMeta.none();
        }

        return new MockMeta(
                asInteger(raw.get("status")),
                asString(raw.get("contentType")),
                asHeaders(raw.get("headers")),
                asKind(raw.get("kind")));
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, String> asHeaders(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        map.forEach((key, header) -> headers.put(String.valueOf(key), String.valueOf(header)));
        return headers;
    }

    private static MockDocument.Kind asKind(Object value) {
        return value == null
                ? null
                : MockDocument.Kind.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
    }
}
