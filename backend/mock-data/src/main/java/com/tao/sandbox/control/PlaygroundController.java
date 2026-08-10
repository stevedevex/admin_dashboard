package com.tao.sandbox.control;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.control.view.PlaygroundDraft;
import com.tao.sandbox.control.view.PlaygroundResult;
import com.tao.sandbox.control.view.ResolveRequest;
import com.tao.sandbox.observe.RequestLog;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SequencedMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Send a request for real, and see exactly what comes back.
 *
 * <p>The neighbouring question to the dry run, and a different one. {@link ResolveController}
 * answers "which file would answer this, and why" — invaluable when a request is not matching, and
 * silent about the thing a client actually consumes. What leaves this server is not the file an
 * author edits: it is that payload wrapped in an envelope, given a status chosen by a sidecar or the
 * contract, carrying headers written in neither place. Before this endpoint, the only way to see
 * those bytes was to leave the dashboard for curl — at precisely the moment somebody is unsure the
 * sandbox works at all.
 *
 * <h2>Why it calls itself over HTTP</h2>
 *
 * <p>The obvious implementation resolves the mock here and formats a response from it. That would be
 * a second serving path, and the moment it existed it would start drifting from the real one — the
 * envelope wrapping, the fault shapes, the status precedence, the sidecar headers, the request log.
 * Each of those is a rule with exactly one home today, and a playground answering from a copy would
 * be most confidently wrong exactly where somebody is using it to settle a disagreement.
 *
 * <p>So it makes a genuine HTTP request to this same server. The response is a client's response,
 * because a client is what made it. What that costs is one loopback hop, which is invisible next to
 * the round trip the dashboard already made to ask.
 *
 * <h2>Why it cannot be pointed anywhere</h2>
 *
 * <p>An endpoint that accepts a path and fetches it is a request-forgery primitive unless the target
 * is pinned. Two things pin it: the host is always loopback and is never read from the request, and
 * the path must first match a route the sandbox actually registered — so the only reachable targets
 * are this server's own mock endpoints. Nothing the caller sends can widen that, including an
 * absolute URL, whose authority is discarded and whose path is validated like any other.
 */
@RestController
class PlaygroundController {

    /**
     * Headers {@link HttpClient} refuses to let a caller set, because it owns them. Filtered rather
     * than rejected: someone pasting a captured request in has no reason to care that this client
     * computes its own {@code Content-Length}, and failing the whole call over one ignored header
     * would be a worse answer than making it.
     */
    private static final Set<String> CLIENT_OWNED =
            Set.of(
                    "connection",
                    "content-length",
                    "date",
                    "expect",
                    "from",
                    "host",
                    "upgrade",
                    "via",
                    "warning");

    /** Long enough for any mock, short enough that a wedged one does not hang the dashboard. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ActiveScenario activeScenario;
    private final RequestTargets targets;
    private final SandboxProperties properties;
    private final PlaygroundDrafts drafts;

    /**
     * Redirects are never followed: the point is to report what the server said, and a client that
     * quietly chased a 302 would hide the one response the caller asked to see.
     */
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    PlaygroundController(
            ActiveScenario activeScenario,
            RequestTargets targets,
            SandboxProperties properties,
            PlaygroundDrafts drafts) {
        this.activeScenario = activeScenario;
        this.targets = targets;
        this.properties = properties;
        this.drafts = drafts;
    }

    /**
     * A request to start from, composed from the contract and the keys of a mock that already
     * exists.
     *
     * <p>A read, done as a POST because its input is a map of key values rather than something that
     * belongs in a query string — the same reason {@code /mocks/name} is a POST. Nothing is created
     * or sent by asking.
     */
    @PostMapping(
            value = "/__tao/playground/draft",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlaygroundDraft draft(@RequestBody PlaygroundDraftRequest request) {
        if (request == null) {
            throw ControlPanelProblem.badRequest(
                    "empty-request", "Empty request", "Name a service and an operation to draft a request for");
        }
        return drafts.draft(request.serviceId(), request.operationId(), request.keys());
    }

    /** @param keys values to write at the locations the operation's own key declarations name. */
    record PlaygroundDraftRequest(String serviceId, String operationId, Map<String, String> keys) {}

    /**
     * @param incoming used only for its local port. The sandbox has to know where to reach itself,
     *     and the port it is actually listening on is a property of the connection this request
     *     arrived on — asking configuration instead would be wrong wherever the port is chosen at
     *     startup, which is every test and any container that maps it.
     */
    @PostMapping(
            value = "/__tao/playground",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlaygroundResult send(@RequestBody ResolveRequest request, HttpServletRequest incoming) {
        String scenarioId =
                request == null || request.scenarioId() == null || request.scenarioId().isBlank()
                        ? activeScenario.get()
                        : request.scenarioId();

        // Identified before anything is sent, and by the same code the dry run uses: an unroutable
        // request is refused here, in the dashboard's vocabulary, rather than being posted at the
        // server to come back as whatever the container makes of it.
        RequestTargets.Target target = targets.locate(request, scenarioId);

        Call call =
                switch (target) {
                    case RequestTargets.Target.Rest rest -> restCall(request, rest);
                    case RequestTargets.Target.Soap soap -> soapCall(request, soap);
                };

        URI url = URI.create("http://127.0.0.1:" + incoming.getLocalPort() + call.pathAndQuery());
        Exchange exchange = exchange(url, call, request, scenarioId);
        HttpResponse<String> response = exchange.response();

        return new PlaygroundResult(
                target.operation().serviceId(),
                target.operation().operationId(),
                scenarioId,
                url.toString(),
                response.statusCode(),
                headersOf(response),
                response.body(),
                exchange.tookMillis(),
                response.headers().firstValue(RequestLog.REQUEST_ID_HEADER).orElse(null),
                targets.discarded(target));
    }

    // --- what to send ------------------------------------------------------

    /** A request reduced to what this client needs, with the protocol differences already settled. */
    private record Call(String method, String pathAndQuery, String body, String contentType) {}

    private record Exchange(HttpResponse<String> response, long tookMillis) {}

    /**
     * The path and query as given, and nothing else from the URL. An absolute URL's authority is
     * discarded here — that is what keeps the target pinned to this server.
     */
    private Call restCall(ResolveRequest request, RequestTargets.Target.Rest rest) {
        URI uri = rest.uri();
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? request.path() : uri.getRawPath();
        String pathAndQuery = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();

        boolean hasBody = request.body() != null && !request.body().isBlank();

        return new Call(
                rest.operation().method().name(),
                pathAndQuery,
                hasBody ? request.body() : null,
                hasBody ? contentTypeOr(request, MediaType.APPLICATION_JSON_VALUE) : null);
    }

    /**
     * A SOAP service has one endpoint and one verb, both known from the contract rather than asked
     * for. The envelope is sent exactly as pasted — reserialising the parsed document would send
     * something subtly different from what the caller is asking about.
     */
    private Call soapCall(ResolveRequest request, RequestTargets.Target.Soap soap) {
        return new Call(
                "POST",
                soap.service().path(),
                request.body(),
                contentTypeOr(request, soap.version().contentType()));
    }

    private String contentTypeOr(ResolveRequest request, String fallback) {
        return request.contentType() == null || request.contentType().isBlank()
                ? fallback
                : request.contentType();
    }

    // --- sending it --------------------------------------------------------

    private Exchange exchange(URI url, Call call, ResolveRequest request, String scenarioId) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(url)
                        .timeout(TIMEOUT)
                        .method(
                                call.method(),
                                call.body() == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(call.body()));

        if (call.contentType() != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, call.contentType());
        }

        // Anything the caller supplied, minus what this client owns. Set before the sandbox's own
        // headers so neither the scenario nor the source marker can be spoofed away.
        if (request.headers() != null) {
            request.headers().forEach(
                    (name, value) -> {
                        if (name != null
                                && value != null
                                && !CLIENT_OWNED.contains(name.toLowerCase(Locale.ROOT))
                                && !name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)) {
                            builder.header(name, value);
                        }
                    });
        }

        // The scenario travels as the override header, which is how a real client asks for one —
        // so the playground can try any scenario without disturbing what the sandbox is serving
        // everyone else.
        builder.setHeader(properties.scenario().header(), scenarioId);
        builder.setHeader(RequestLog.SOURCE_HEADER, RequestLog.Source.PLAYGROUND.name());

        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Exchange(response, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        } catch (IOException e) {
            throw ControlPanelProblem.badGateway(
                    "loopback-failed",
                    "The sandbox could not call itself",
                    "%s did not answer: %s".formatted(url, e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ControlPanelProblem.badGateway(
                    "loopback-interrupted", "Interrupted", "The call to %s was interrupted".formatted(url));
        }
    }

    /**
     * Names arrive lower-cased, because {@link HttpClient} normalises them and does not keep the
     * casing the server wrote. Left that way rather than title-cased back: header names are
     * case-insensitive and HTTP/2 requires lower case, so inventing a capitalisation would be
     * presenting a guess as what was on the wire.
     */
    private SequencedMap<String, String> headersOf(HttpResponse<String> response) {
        SequencedMap<String, String> headers = new LinkedHashMap<>();

        // Joined rather than kept as lists. A repeated header is legal and the sandbox never sends
        // one; presenting every value as an array to make room for a case that cannot arise would
        // cost every reader of this panel something and buy nothing.
        response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));

        return headers;
    }
}
