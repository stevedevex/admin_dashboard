package com.tao.sandbox.control;

import com.tao.sandbox.control.view.ResolveRequest;
import com.tao.sandbox.control.view.ResolveResult;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.runtime.resolve.MockPipeline;
import com.tao.sandbox.runtime.resolve.OperationLocator;
import com.tao.sandbox.runtime.resolve.ResolutionTrace;
import java.util.LinkedHashMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dry run: paste a request, find out what it would get.
 *
 * <p>Nothing is served and nothing is stored. It runs the real {@link MockPipeline} rather than a
 * reimplementation of the matching rules — a dry run with its own copy of them would agree with the
 * server right up until they drifted, which is exactly when someone would be relying on it.
 *
 * <p>The same applies to the step before matching, which this once did carry its own copy of:
 * identifying the operation goes through {@link OperationLocator} by way of {@link RequestTargets},
 * so a request the dry run says lands on an operation is one the router would have sent there.
 *
 * <p>Distinct from the playground, which answers the neighbouring question by serving the request
 * for real. This endpoint exists for the times the answer wanted is "why did that not match", where
 * a response body is noise and a call that actually lands in the log is a side effect nobody asked
 * for.
 */
@RestController
class ResolveController {

    private final MockPipeline pipeline;
    private final ActiveScenario activeScenario;
    private final RequestTargets targets;

    ResolveController(MockPipeline pipeline, ActiveScenario activeScenario, RequestTargets targets) {
        this.pipeline = pipeline;
        this.activeScenario = activeScenario;
        this.targets = targets;
    }

    @PostMapping(
            value = "/__tao/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResolveResult resolve(@RequestBody ResolveRequest request) {
        String scenarioId =
                request == null || request.scenarioId() == null || request.scenarioId().isBlank()
                        ? activeScenario.get()
                        : request.scenarioId();

        RequestTargets.Target target = targets.locate(request, scenarioId);

        MockPipeline.Outcome outcome = pipeline.resolve(target.operation(), target.facade());
        ResolutionTrace trace = outcome.trace();

        return new ResolveResult(
                trace.serviceId(),
                trace.operationId(),
                trace.scenarioId(),
                new LinkedHashMap<>(trace.extracted()),
                targets.discarded(target),
                trace.attempted(),
                trace.matched() == null ? null : trace.matched().asPath(),
                trace.inherited(),
                trace.took().toMillis());
    }
}
