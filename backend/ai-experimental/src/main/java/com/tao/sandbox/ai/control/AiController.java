package com.tao.sandbox.ai.control;

import com.tao.sandbox.ai.AiProperties;
import com.tao.sandbox.ai.PayloadGeneration;
import com.tao.sandbox.ai.PayloadGenerator;
import com.tao.sandbox.ai.llm.ModelProvider;
import com.tao.sandbox.control.ControlPanelProblem;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generating a payload for an operation, on request.
 *
 * <p>Mounted under {@code /__tao} like the rest of the control plane, and under {@code /ai} within
 * it so what is model-generated is legible from the path alone.
 *
 * <p>Nothing here writes. Generation answers with a proposal and the verdict on it; saving is the
 * ordinary {@code PUT} an author makes after reading what came back, which keeps one way for a
 * payload to enter the library.
 *
 * <p>The generator is optional because a sandbox with no provider configured has none — see {@code
 * AiConfiguration}. This is the one place that absence is handled, which is why generation itself
 * never has to ask whether it can run.
 */
@RestController
class AiController {

    private final Optional<PayloadGenerator> generator;
    private final Optional<ModelProvider> provider;
    private final AiProperties properties;

    AiController(
            Optional<PayloadGenerator> generator, Optional<ModelProvider> provider, AiProperties properties) {
        this.generator = generator;
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * Whether generation can be offered, and by what.
     *
     * <p>Asked before the action is drawn, so the dashboard can disable it and say why rather than
     * presenting a button that fails on click. {@code generator} is {@code none} when nothing is
     * configured — reported rather than omitted, because "not set up" and "set up but unreachable"
     * are different problems with different fixes.
     */
    @GetMapping(value = "/__tao/ai/status", produces = MediaType.APPLICATION_JSON_VALUE)
    AiStatus status() {
        boolean ready = generator.isPresent() && provider.map(ModelProvider::available).orElse(false);

        return new AiStatus(
                ready, provider.map(ModelProvider::name).orElse("none"), properties.model(), reasonWhenUnavailable(ready));
    }

    @PostMapping(
            value = "/__tao/ai/payload",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PayloadGeneration generate(@RequestBody GenerateRequest request) {
        if (request == null || request.serviceId() == null || request.operationId() == null) {
            throw ControlPanelProblem.badRequest(
                    "missing-operation",
                    "Missing operation",
                    "Generation needs a serviceId and operationId — the contract is what it generates against");
        }

        PayloadGenerator ready =
                generator.orElseThrow(
                        () ->
                                ControlPanelProblem.unprocessable(
                                        "generator-not-configured",
                                        "AI is not configured",
                                        "No model provider is configured. Set tao.sandbox.ai.endpoint (and the"
                                                + " credentials under tao.sandbox.ai.auth) to enable generation."));

        if (!provider.map(ModelProvider::available).orElse(false)) {
            throw ControlPanelProblem.unprocessable(
                    "generator-unavailable",
                    "AI is unavailable",
                    "The configured model provider cannot be reached, or its credentials were rejected."
                            + " Nothing was generated.");
        }

        try {
            return ready.generate(
                    request.serviceId(), request.operationId(), request.prompt(), request.current());
        } catch (IllegalArgumentException e) {
            // An operation that is not served, or one whose contract declares no response schema.
            // Both are answers about the request rather than faults, and the message says which.
            throw ControlPanelProblem.unprocessable(
                    "nothing-to-generate", "Nothing to generate against", e.getMessage());
        }
    }

    /** Said in the dashboard's own words, so the button can explain itself without a second call. */
    private String reasonWhenUnavailable(boolean ready) {
        if (ready) {
            return null;
        }

        return generator.isEmpty()
                ? "No model provider is configured for this sandbox."
                : "The configured model provider cannot be reached, or its credentials were rejected.";
    }
}
