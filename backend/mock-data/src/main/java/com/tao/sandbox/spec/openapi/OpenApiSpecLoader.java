package com.tao.sandbox.spec.openapi;

import com.tao.sandbox.config.SandboxProperties.OperationConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceConfig;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.spec.OperationDefinition;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Reconciles an OpenAPI document with the operations configuration names.
 *
 * <p>Handles 3.0 and 3.1 — the parser abstracts the JSON Schema differences, which is the main
 * reason to use it rather than reading the YAML directly.
 */
@Component
public class OpenApiSpecLoader {

    /**
     * Everything one OpenAPI document yields.
     *
     * @param responseSchemas operationId → the success response's JSON Schema, for operations that
     *     declare one. Kept apart from {@link OperationDefinition} because it is control-panel
     *     material — potentially many kilobytes per operation — and request handling never reads
     *     it.
     */
    public record Loaded(List<OperationDefinition> operations, Map<String, String> responseSchemas) {}

    /**
     * @param problems appended to rather than thrown, so startup can report every fault at once
     */
    public Loaded load(ServiceConfig service, List<String> problems) {
        OpenAPI document = parse(service, problems);
        if (document == null || document.getPaths() == null) {
            return new Loaded(List.of(), Map.of());
        }

        Map<String, Located> byOperationId = indexByOperationId(document);
        List<OperationDefinition> definitions = new ArrayList<>();
        Map<String, String> schemas = new LinkedHashMap<>();

        for (OperationConfig configured : service.operations()) {
            String operationId = configured.operationId();

            if (operationId == null || operationId.isBlank()) {
                problems.add("%s: REST operations must declare an operationId".formatted(service.id()));
                continue;
            }

            Located located = byOperationId.get(operationId);
            if (located == null) {
                problems.add(
                        "%s: operationId '%s' is not in %s. Available: %s"
                                .formatted(
                                        service.id(),
                                        operationId,
                                        service.spec(),
                                        byOperationId.keySet().stream().sorted().toList()));
                continue;
            }

            List<KeySpec> keys = parseKeys(service, configured, problems);
            Success success = declaredSuccess(document, located.operation());

            if (success.schema() != null) {
                schemas.put(operationId, success.schema());
            }

            definitions.add(
                    new OperationDefinition(
                            service.id(),
                            operationId,
                            located.method(),
                            joinPath(service.basePath(), located.path()),
                            success.status(),
                            success.contentType(),
                            keys,
                            configured.strategy()));
        }

        return new Loaded(definitions, schemas);
    }

    private OpenAPI parse(ServiceConfig service, List<String> problems) {
        if (service.spec() == null || service.spec().isBlank()) {
            problems.add("%s: REST services must declare a 'spec'".formatted(service.id()));
            return null;
        }

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        // Inlined rather than left as $ref because the schema is handed whole to the dashboard by
        // GET /__tao/services/{s}/operations/{o}/schema. A schema pointing at
        // #/components/schemas/Pet is unusable to a caller that was never given the components
        // section, and shipping the whole document instead would make the endpoint useless for
        // showing what one operation returns.
        options.setResolveFully(true);

        var result = new OpenAPIV3Parser().readLocation(stripClasspathPrefix(service.spec()), null, options);

        if (result.getOpenAPI() == null) {
            problems.add(
                    "%s: could not parse %s — %s"
                            .formatted(
                                    service.id(),
                                    service.spec(),
                                    result.getMessages() == null ? "no detail" : result.getMessages()));
            return null;
        }

        return result.getOpenAPI();
    }

    /**
     * swagger-parser resolves classpath resources without the Spring prefix, so it is stripped
     * here rather than making callers use a different notation from the rest of the configuration.
     */
    private String stripClasspathPrefix(String location) {
        return location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
    }

    private List<KeySpec> parseKeys(ServiceConfig service, OperationConfig configured, List<String> problems) {
        List<KeySpec> keys = new ArrayList<>();
        for (String declaration : configured.keys()) {
            try {
                keys.add(KeySpec.parse(declaration));
            } catch (IllegalArgumentException e) {
                problems.add("%s/%s: %s".formatted(service.id(), configured.name(), e.getMessage()));
            }
        }
        return keys;
    }

    private Map<String, Located> indexByOperationId(OpenAPI document) {
        Map<String, Located> index = new LinkedHashMap<>();

        document.getPaths()
                .forEach(
                        (path, item) ->
                                methodsOf(item)
                                        .forEach(
                                                (method, operation) -> {
                                                    if (operation.getOperationId() != null) {
                                                        index.put(
                                                                operation.getOperationId(),
                                                                new Located(path, method, operation));
                                                    }
                                                }));

        return index;
    }

    private Map<HttpMethod, Operation> methodsOf(PathItem item) {
        Map<HttpMethod, Operation> methods = new LinkedHashMap<>();
        putIfPresent(methods, HttpMethod.GET, item.getGet());
        putIfPresent(methods, HttpMethod.POST, item.getPost());
        putIfPresent(methods, HttpMethod.PUT, item.getPut());
        putIfPresent(methods, HttpMethod.PATCH, item.getPatch());
        putIfPresent(methods, HttpMethod.DELETE, item.getDelete());
        return methods;
    }

    private void putIfPresent(Map<HttpMethod, Operation> target, HttpMethod method, Operation operation) {
        if (operation != null) {
            target.put(method, operation);
        }
    }

    private String joinPath(String basePath, String specPath) {
        String base = basePath == null ? "" : basePath.replaceAll("/+$", "");
        String tail = specPath.startsWith("/") ? specPath : "/" + specPath;
        return base + tail;
    }

    private record Located(String path, HttpMethod method, Operation operation) {}

    /** @param schema the response body's JSON Schema, or null when the contract declares none */
    private record Success(int status, String contentType, String schema) {}

    /**
     * The status, media type and schema the contract declares for success.
     *
     * <p>Read from the spec rather than assumed, so a client sees the 201 and the media type it
     * was promised. The lowest 2xx wins when several are declared; a mock can still override
     * either through its sidecar.
     */
    private Success declaredSuccess(OpenAPI document, Operation operation) {
        int status = 200;
        String contentType = MediaType.APPLICATION_JSON_VALUE;

        if (operation.getResponses() == null) {
            return new Success(status, contentType, null);
        }

        Optional<Integer> lowest2xx =
                operation.getResponses().keySet().stream()
                        .filter(code -> code.length() == 3 && code.startsWith("2"))
                        .map(Integer::parseInt)
                        .min(Integer::compareTo);

        if (lowest2xx.isEmpty()) {
            return new Success(status, contentType, null);
        }

        status = lowest2xx.get();
        ApiResponse response = operation.getResponses().get(String.valueOf(status));
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            return new Success(status, contentType, null);
        }

        contentType = response.getContent().keySet().iterator().next();
        return new Success(status, contentType, schemaOf(document, response.getContent().get(contentType)));
    }

    /**
     * The declared schema as JSON text.
     *
     * <p>Serialised with swagger's own mapper rather than a plain one: the model classes carry
     * bookkeeping fields such as {@code exampleSetFlag} that only swagger's configuration knows to
     * omit, and 3.0 and 3.1 disagree on how a type is written — {@code "type": "string"} against
     * {@code "type": ["string", "null"]}. Emitting the dialect the document was written in is what
     * keeps the result a schema a validator will accept.
     */
    private String schemaOf(OpenAPI document, io.swagger.v3.oas.models.media.MediaType media) {
        if (media == null || media.getSchema() == null) {
            return null;
        }
        return document.getSpecVersion() == SpecVersion.V31
                ? Json31.pretty(media.getSchema())
                : Json.pretty(media.getSchema());
    }
}
