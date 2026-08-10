package com.tao.sandbox.runtime.rest;

import com.tao.sandbox.observe.RequestLog;
import com.tao.sandbox.runtime.resolve.MockPipeline;
import com.tao.sandbox.runtime.resolve.ResolutionTrace;
import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.SpecRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Client-facing REST endpoints, generated from the spec at startup.
 *
 * <p>Routes are registered per operation so Spring performs URI-template matching and unconfigured
 * paths never reach the pipeline. Operations are grouped by path first, so that a path we do serve
 * can answer 405 for a method we do not — a real service distinguishes "wrong verb" from "no such
 * resource", and a client's error handling often does too.
 */
@Configuration
public class RestRoutes {

    private static final Logger log = LoggerFactory.getLogger(RestRoutes.class);

    @Bean
    RouterFunction<ServerResponse> mockRoutes(SpecRegistry registry, MockPipeline pipeline, RequestLog requests) {
        RouterFunctions.Builder routes = RouterFunctions.route();

        Map<String, List<OperationDefinition>> byPath =
                registry.restOperations().stream()
                        .collect(Collectors.groupingBy(OperationDefinition::path, LinkedHashMap::new, Collectors.toList()));

        byPath.forEach(
                (path, operations) -> {
                    for (OperationDefinition operation : operations) {
                        routes.route(
                                RequestPredicates.method(operation.method()).and(RequestPredicates.path(path)),
                                request -> handle(operation, request, pipeline, requests));

                        log.info(
                                "Routing {} {} -> {}/{} ({} {})",
                                operation.method(),
                                path,
                                operation.serviceId(),
                                operation.operationId(),
                                operation.successStatus(),
                                operation.responseContentType());
                    }

                    // Registered after the real methods, so it only catches what they did not.
                    routes.route(RequestPredicates.path(path), request -> methodNotAllowed(operations));
                });

        return routes.build();
    }

    private ServerResponse handle(
            OperationDefinition operation, ServerRequest request, MockPipeline pipeline, RequestLog requests) {

        ServerRequestFacade facade = new ServerRequestFacade(request);
        RequestLog.Source source = RequestLog.Source.of(request.headers().firstHeader(RequestLog.SOURCE_HEADER));
        var outcome = pipeline.resolve(operation, facade);

        if (outcome.document().isEmpty()) {
            long logged =
                    requests.record(
                            outcome.trace(), HttpStatus.NOT_FOUND.value(), facade.rawBody(), null, source);

            // Loud, never empty. An empty body here would reproduce exactly the upstream
            // behaviour the sandbox exists to eliminate, and would do it invisibly.
            return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .header(RequestLog.REQUEST_ID_HEADER, String.valueOf(logged))
                    .body(missBody(outcome.trace()));
        }

        var document = outcome.document().get();
        var meta = document.meta();

        // Precedence: the mock's sidecar, then what the contract declares, then a default.
        int status = meta.statusOr(operation.successStatus());
        long logged = requests.record(outcome.trace(), status, facade.rawBody(), document.body(), source);

        var response =
                ServerResponse.status(status)
                        .header("Content-Type", meta.contentTypeOr(operation.responseContentType()));
        meta.headers().forEach(response::header);

        // After the mock's own headers: the id is what the server did with this call, not something
        // a stored response gets to describe.
        response.header(RequestLog.REQUEST_ID_HEADER, String.valueOf(logged));

        return response.body(document.body());
    }

    private ServerResponse methodNotAllowed(List<OperationDefinition> operations) {
        String allowed =
                operations.stream().map(OperationDefinition::method).map(HttpMethod::name).collect(Collectors.joining(", "));

        return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", allowed).build();
    }

    private Map<String, Object> missBody(ResolutionTrace trace) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:tao:sandbox:no-mock");
        problem.put("title", "No mock matched this request");
        problem.put("status", 404);
        problem.put("service", trace.serviceId());
        problem.put("operation", trace.operationId());
        problem.put("scenario", trace.scenarioId());
        problem.put("extracted", trace.extracted());
        problem.put("attempted", trace.attempted());
        problem.put(
                "detail", "Create one of the listed files, or check the declared keys for this operation.");
        return problem;
    }
}
