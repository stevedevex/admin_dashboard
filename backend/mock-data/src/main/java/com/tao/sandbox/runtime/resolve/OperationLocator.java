package com.tao.sandbox.runtime.resolve;

import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapOperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.QName;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Which operation a request is for, decided in one place.
 *
 * <p>This question was answered three times. The SOAP handler mapped a body element to an
 * operation with its own three-way outcome — unknown element, known but unconfigured, served — and
 * the dry run mapped the same element with a second copy of the same reasoning. The dry run also
 * matched REST paths against the registry itself, which is a reimplementation of what the router
 * did when it registered those paths at startup.
 *
 * <p>That last one is worth naming plainly, because the dry run's own documentation said the
 * opposite: it claimed to avoid a second copy of the matching rules by running the real pipeline.
 * True of resolution, and never true of identification — the step before it. A dry run that
 * identifies operations differently from the server answers confidently about a request the server
 * would have routed somewhere else, and it does so in the one situation where somebody is using it
 * precisely because they do not trust their own reading of the configuration.
 *
 * <p>Static and free of Spring: every input is passed in, so this can be exercised against
 * hand-built definitions without a contract, a registry or a context. The callers hold the
 * registry; deciding is separate from looking up.
 */
public final class OperationLocator {

    private static final PathPatternParser PATHS = PathPatternParser.defaultInstance;

    private OperationLocator() {}

    /** A REST operation and the path variables its template captured. */
    public record RestMatch(OperationDefinition operation, Map<String, String> pathVariables) {}

    /**
     * The first operation whose method and path template accept this request.
     *
     * <p>Matched against the same patterns the router registered, so a path that resolves here is
     * one the router would have accepted.
     */
    public static Optional<RestMatch> forRest(
            List<OperationDefinition> operations, String method, PathContainer path) {

        for (OperationDefinition operation : operations) {
            if (method == null || !operation.method().name().equalsIgnoreCase(method)) {
                continue;
            }

            PathPattern.PathMatchInfo match = PATHS.parse(operation.path()).matchAndExtract(path);
            if (match != null) {
                return Optional.of(new RestMatch(operation, match.getUriVariables()));
            }
        }

        return Optional.empty();
    }

    /**
     * What a SOAP body element turns out to be.
     *
     * <p>Three outcomes, not two. "In the contract but not configured" is a different answer from
     * "nothing serves this", and both callers say so differently — a 501 fault on the wire, a
     * {@code not configured} problem in the dashboard. Collapsing them would report a contract
     * mismatch as a typo.
     */
    public sealed interface SoapMatch {

        /** No service maps that element. */
        record Unknown() implements SoapMatch {}

        /** Declared by the contract, absent from configuration — NOT_IMPLEMENTED. */
        record NotConfigured(SoapServiceDefinition service, String operationName) implements SoapMatch {}

        record Served(SoapServiceDefinition service, SoapOperationDefinition operation) implements SoapMatch {}
    }

    /** Within one service, which is what the live endpoint knows from the URL it was called on. */
    public static SoapMatch forSoap(SoapServiceDefinition service, QName bodyElement) {
        String operationName = service.elementToOperation().get(bodyElement);
        if (operationName == null) {
            return new SoapMatch.Unknown();
        }

        SoapOperationDefinition operation = service.served().get(operationName);
        return operation == null
                ? new SoapMatch.NotConfigured(service, operationName)
                : new SoapMatch.Served(service, operation);
    }

    /**
     * Across every service, which is what a pasted envelope needs: it carries no endpoint, so the
     * element is the only thing that says which contract it belongs to.
     */
    public static SoapMatch forSoap(List<SoapServiceDefinition> services, QName bodyElement) {
        for (SoapServiceDefinition service : services) {
            SoapMatch match = forSoap(service, bodyElement);
            if (!(match instanceof SoapMatch.Unknown)) {
                return match;
            }
        }
        return new SoapMatch.Unknown();
    }
}
