package com.tao.sandbox.runtime.resolve;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.runtime.match.KeyExtractor;
import com.tao.sandbox.runtime.match.RequestFacade;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockProvider;
import com.tao.sandbox.store.MockQuery;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The one pipeline. REST and SOAP differ only in how a request is identified and read; from here
 * down the behaviour is shared.
 */
@Service
public class MockPipeline {

    private final KeyExtractor extractor;
    private final MockProvider provider;
    private final SandboxProperties properties;
    private final ActiveScenario activeScenario;

    public MockPipeline(
            KeyExtractor extractor,
            MockProvider provider,
            SandboxProperties properties,
            ActiveScenario activeScenario) {
        this.extractor = extractor;
        this.provider = provider;
        this.properties = properties;
        this.activeScenario = activeScenario;
    }

    public record Outcome(Optional<MockDocument> document, ResolutionTrace trace) {}

    public Outcome resolve(ServedOperation operation, RequestFacade request) {
        long startedAt = System.nanoTime();

        String scenarioId =
                request.header(properties.scenario().header())
                        .filter(value -> !value.isBlank())
                        .orElseGet(activeScenario::get);

        var extraction = extractor.extract(operation.keys(), operation.strategy(), request);

        MockQuery query =
                new MockQuery(
                        scenarioId,
                        operation.serviceId(),
                        operation.operationId(),
                        extraction.satisfied() ? extraction.keys() : new LinkedHashMap<>(),
                        operation.strategy().matchesSubsets()
                                ? MockQuery.Matching.BEST
                                : MockQuery.Matching.EXACT);

        Optional<MockProvider.Resolved> resolved = provider.resolve(query);

        ResolutionTrace trace =
                new ResolutionTrace(
                        operation.serviceId(),
                        operation.operationId(),
                        scenarioId,
                        extraction.keys(),
                        provider.candidates(query),
                        resolved.map(MockProvider.Resolved::id).orElse(null),
                        resolved.map(MockProvider.Resolved::inherited).orElse(false),
                        Duration.ofNanos(System.nanoTime() - startedAt));

        return new Outcome(resolved.map(MockProvider.Resolved::document), trace);
    }
}
