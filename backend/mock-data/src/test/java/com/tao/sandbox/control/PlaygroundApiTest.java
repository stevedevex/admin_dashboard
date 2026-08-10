package com.tao.sandbox.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The playground, exercised over a real socket.
 *
 * <p>{@code MockMvc} is deliberately not used here, though every other control-panel test does. The
 * endpoint's entire claim is that it answers by making a genuine HTTP call to the server it runs in,
 * and MockMvc has no server and no port — a test through it would pass while proving the opposite of
 * what is being asserted.
 *
 * <p>What the assertions are actually for: each one names something a client sees that the mock file
 * an author edits does not contain. A status from a sidecar, a header from a sidecar, an envelope the
 * server wrapped. If the playground ever starts formatting its own responses instead of asking the
 * data plane for one, these are the facts that would quietly stop being true.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaygroundApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TRADE_PRICE_REQUEST =
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
              <soapenv:Body>
                <TradePriceRequest xmlns="http://example.com/stockquote.xsd">
                  <tickerSymbol>IBM</tickerSymbol>
                </TradePriceRequest>
              </soapenv:Body>
            </soapenv:Envelope>
            """;

    /**
     * The JDK client rather than a Spring test client, for the same reason MockMvc is avoided: this
     * test is about real HTTP, and the fewer layers between the assertion and the socket the less
     * there is to mistake for the thing under test.
     */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void aRestCallComesBackAsAClientWouldHaveReceivedIt() {
        JsonNode result = send(Map.of("method", "GET", "path", "/petstore/v1/pets/1"));

        assertThat(result.get("status").asInt()).isEqualTo(200);
        assertThat(result.get("body").asString()).contains("Fido");
        assertThat(result.get("serviceId").asString()).isEqualTo("petstore");
        assertThat(result.get("operationId").asString()).isEqualTo("showPetById");
        assertThat(result.get("scenarioId").asString()).isEqualTo("baseline");

        // The loopback is stated rather than implied: a reader comparing this against their own
        // client's configuration is the point of showing it.
        assertThat(result.get("url").asString()).contains("127.0.0.1").endsWith("/petstore/v1/pets/1");
    }

    /**
     * The reason the endpoint exists. None of the status, the media type or the {@code Retry-After}
     * is in {@code error-cases/petstore/showPetById/_default.json} — they are in the sidecar beside
     * it, and before this endpoint the only way to see them applied was to leave for curl.
     */
    @Test
    void theResponseCarriesWhatTheSidecarDecidedRatherThanWhatTheFileHolds() {
        JsonNode result =
                send(Map.of("method", "GET", "path", "/petstore/v1/pets/99", "scenarioId", "error-cases"));

        assertThat(result.get("status").asInt()).isEqualTo(503);

        // Lower-cased because that is how they come off the wire client; see PlaygroundController.
        assertThat(result.get("headers").get("retry-after").asString()).isEqualTo("30");
        assertThat(result.get("headers").get("content-type").asString()).contains("application/problem+json");
    }

    /**
     * A SOAP mock stores a bare payload; what leaves the server is that payload inside an envelope,
     * with the service's response header applied. Asserting on both is what distinguishes serving
     * from reading a file.
     */
    @Test
    void aSoapResponseComesBackWrapped() {
        JsonNode result = send(Map.of("body", TRADE_PRICE_REQUEST));

        assertThat(result.get("status").asInt()).isEqualTo(200);
        assertThat(result.get("operationId").asString()).isEqualTo("GetLastTradePrice");

        String body = result.get("body").asString();
        assertThat(body).contains("Envelope").contains("<price>184.25</price>");
    }

    /** The trace is not duplicated into the result — it is fetched by the id the response carried. */
    @Test
    void theCallIsLoggedAndTheResultNamesItsEntry() {
        JsonNode result = send(Map.of("method", "GET", "path", "/petstore/v1/pets/1"));

        String requestId = result.get("requestId").asString();
        assertThat(requestId).isNotBlank();

        JsonNode entry = get("/__tao/requests/" + requestId);
        assertThat(entry.get("source").asString()).isEqualTo("PLAYGROUND");
        assertThat(entry.get("operationId").asString()).isEqualTo("showPetById");
        assertThat(entry.get("matched").asString()).endsWith("petid=1.json");
    }

    /**
     * Trying a scenario is not switching to it. The override travels as the header a real client
     * would use, so a shared instance keeps serving everyone else whatever it was already serving.
     */
    @Test
    void tryingAScenarioLeavesWhatTheSandboxServesAlone() {
        send(Map.of("method", "GET", "path", "/petstore/v1/pets/99", "scenarioId", "error-cases"));

        assertThat(get("/__tao/status").get("activeScenario").asString()).isEqualTo("baseline");
    }

    /**
     * Refused before a socket is opened, and in the dashboard's vocabulary. This is also what keeps
     * the endpoint from being a request-forgery primitive: only paths the sandbox registered are
     * reachable, so there is nothing to point anywhere.
     */
    @Test
    void nothingIsSentForAPathNoRouteServes() {
        HttpResponse<String> response = post(Map.of("method", "GET", "path", "/not/a/route"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("no-such-route");
    }

    /** An absolute URL's authority is discarded; only its path is honoured. */
    @Test
    void anAbsoluteUrlCannotAimTheCallSomewhereElse() {
        JsonNode result =
                send(Map.of("method", "GET", "path", "http://example.invalid/petstore/v1/pets/1"));

        assertThat(result.get("url").asString()).contains("127.0.0.1").doesNotContain("example.invalid");
        assertThat(result.get("body").asString()).contains("Fido");
    }

    /**
     * The one part of the trace the log cannot carry, because enumerating a request's fields means
     * parsing its body and the serving path deliberately never does. Computed here instead, so the
     * playground can answer "that is not what identifies it" without the data plane paying for it on
     * every request.
     */
    @Test
    void fieldsNoDeclaredKeyReadsAreReportedAnyway() {
        JsonNode result =
                send(Map.of("method", "GET", "path", "/petstore/v1/pets/1?correlationId=abc-123"));

        assertThat(result.get("discarded").valueStream().map(JsonNode::asString))
                .contains("correlationId");
    }

    /**
     * The property that makes drafting worth doing: a drafted request resolves to the mock whose
     * keys drafted it. Not "looks plausible" — the values are written at the locations the key
     * declarations read from, so extraction finds them by construction.
     */
    @Test
    void aDraftedSoapRequestResolvesToTheMockItWasDraftedFrom() {
        JsonNode draft =
                draft(
                        Map.of(
                                "serviceId", "stockquote",
                                "operationId", "GetLastTradePrice"),
                        Map.of("tickerSymbol", "IBM"));

        assertThat(draft.get("path").asString()).isEqualTo("/soap/stockquote");
        assertThat(draft.get("body").asString()).contains("TradePriceRequest").contains("IBM");
        assertThat(draft.get("note").isNull()).as("every declared key was supplied").isTrue();

        // Nobody wants to edit an envelope with a blank line between every element, and this is the
        // one place the sandbox serialises a DOM rather than returning bytes as they were written.
        assertThat(draft.get("body").asString().lines().map(String::strip))
                .as("drafted envelope should have no blank lines")
                .doesNotContain("");

        // Sent unmodified: the draft is the whole point only if it works as handed over.
        JsonNode result = send(Map.of("body", draft.get("body").asString()));

        assertThat(result.get("status").asInt()).isEqualTo(200);
        assertThat(result.get("body").asString()).contains("<price>184.25</price>");

        JsonNode entry = get("/__tao/requests/" + result.get("requestId").asString());
        assertThat(entry.get("matched").asString()).endsWith("tickersymbol=ibm.xml");
    }

    /** The REST equivalent: a path variable filled from the key that reads it. */
    @Test
    void aDraftedRestRequestAddressesTheMockItWasDraftedFrom() {
        JsonNode draft =
                draft(Map.of("serviceId", "petstore", "operationId", "showPetById"), Map.of("petId", "2"));

        assertThat(draft.get("method").asString()).isEqualTo("GET");
        assertThat(draft.get("path").asString()).isEqualTo("/petstore/v1/pets/2");

        JsonNode result = send(Map.of("method", "GET", "path", draft.get("path").asString()));
        JsonNode entry = get("/__tao/requests/" + result.get("requestId").asString());

        assertThat(entry.get("matched").asString()).endsWith("petid=2.json");
    }

    /**
     * A draft with nothing supplied is still sendable — it just resolves to the default, and says
     * so. Silence here would leave somebody reading a default as the mock they meant to reach.
     */
    @Test
    void aDraftWithNoKeysSaysWhatItLeftOut() {
        JsonNode draft = draft(Map.of("serviceId", "petstore", "operationId", "showPetById"), Map.of());

        assertThat(draft.get("path").asString()).isEqualTo("/petstore/v1/pets/{petId}");
        assertThat(draft.get("note").asString()).contains("petId").contains("default");
    }

    /**
     * A drafted body is the contract's object, not a bag of the keys.
     *
     * <p>The distinction that matters for a POST. Resolution only ever reads the declared keys, so a
     * body containing nothing else still reaches the right mock — and is not a request the real
     * service would have accepted. Somebody using the playground to check what their client should
     * send would be reading a shape that does not exist, and would find out from the real service.
     *
     * <p>{@code NewPet} declares {@code name} and {@code tag}; only {@code name} identifies. So
     * {@code tag} appearing here is the whole assertion: it is in the draft because the schema says
     * so, and nothing about resolution would have put it there.
     */
    @Test
    void aDraftedBodyIsShapedByTheSchemaAndNotOnlyByTheKeys() {
        JsonNode draft =
                draft(Map.of("serviceId", "petstore", "operationId", "createPets"), Map.of("name", "rex"));

        assertThat(draft.get("method").asString()).isEqualTo("POST");
        assertThat(draft.get("contentType").asString()).contains("application/json");

        JsonNode body = JSON.readTree(draft.get("body").asString());
        assertThat(body.get("name").asString()).as("the key, at the pointer it is read from").isEqualTo("rex");
        assertThat(body.has("tag")).as("declared by NewPet, and identifies nothing").isTrue();
    }

    /** And it still resolves: shaping the body must not disturb where the key landed. */
    @Test
    void aDraftedPostStillReachesTheMockItWasDraftedFrom() {
        JsonNode draft =
                draft(Map.of("serviceId", "petstore", "operationId", "createPets"), Map.of("name", "rex"));

        JsonNode result =
                send(Map.of("method", "POST", "path", draft.get("path").asString(), "body", draft.get("body").asString()));

        assertThat(result.get("status").asInt()).isEqualTo(201);

        JsonNode entry = get("/__tao/requests/" + result.get("requestId").asString());
        assertThat(entry.get("matched").asString()).endsWith("name=rex.json");
    }

    /**
     * Nested fields are named to their leaves, not summarised by their container.
     *
     * <p>{@code owner} on its own says a field arrived and nothing about which part of it was
     * expected to matter. Someone who put {@code owner.id} in a request and did not get the mock they
     * wanted needs to see {@code owner.id} listed, which is the sentence "nothing read that" — and
     * before this the list could only say "nothing read owner".
     */
    @Test
    void nestedFieldsAreReportedByTheirFullPath() {
        JsonNode result =
                send(Map.of(
                        "method", "POST",
                        "path", "/petstore/v1/pets",
                        "body", "{\"name\":\"rex\",\"owner\":{\"id\":\"7\",\"note\":\"vip\"}}"));

        assertThat(result.get("discarded").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("owner.id", "owner.note")
                .as("the key itself is read, so it is never discarded")
                .doesNotContain("name");
    }

    /** An array is a leaf: identity is never inside a list, and one entry per element says nothing. */
    @Test
    void anArrayIsReportedAsItselfRatherThanPerElement() {
        JsonNode result =
                send(Map.of(
                        "method", "POST",
                        "path", "/petstore/v1/pets",
                        "body", "{\"name\":\"rex\",\"tags\":[{\"id\":1},{\"id\":2},{\"id\":3}]}"));

        assertThat(result.get("discarded").valueStream().map(JsonNode::asString)).containsExactly("tags");
    }

    // --- helpers -----------------------------------------------------------

    private JsonNode draft(Map<String, String> operation, Map<String, String> keys) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(operation);
        request.put("keys", keys);

        HttpResponse<String> response =
                exchange(
                        HttpRequest.newBuilder(uri("/__tao/playground/draft"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
                                .build());

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode send(Map<String, String> request) {
        HttpResponse<String> response = post(request);

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(Map<String, String> request) {
        return exchange(
                HttpRequest.newBuilder(uri("/__tao/playground"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
                        .build());
    }

    private JsonNode get(String path) {
        HttpResponse<String> response = exchange(HttpRequest.newBuilder(uri(path)).GET().build());

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AssertionError("Could not reach the sandbox under test", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
