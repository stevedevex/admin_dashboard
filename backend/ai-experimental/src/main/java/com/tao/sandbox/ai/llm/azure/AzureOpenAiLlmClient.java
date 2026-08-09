package com.tao.sandbox.ai.llm.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.tao.sandbox.ai.AiProperties;
import com.tao.sandbox.ai.llm.ChatMessage;
import com.tao.sandbox.ai.llm.ChatRequest;
import com.tao.sandbox.ai.llm.ChatResponse;
import com.tao.sandbox.ai.llm.LlmClient;
import com.tao.sandbox.ai.llm.ResponseFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Azure OpenAI, over its chat-completions API.
 *
 * <p>No Azure OpenAI SDK. The wire format is OpenAI's, the application already has an HTTP client,
 * and what Azure genuinely adds is authentication — which is why {@code azure-identity} is a
 * dependency and an OpenAI SDK is not. The consequence worth stating: this speaks a documented
 * wire contract, so pointing {@code endpoint} at any OpenAI-compatible server works too.
 *
 * <p><strong>Unverified against a live endpoint.</strong> It was written without Azure
 * connectivity, so its request mapping and response parsing are covered by tests against a stubbed
 * server and nothing more. The first real call is the first real test.
 */
public class AzureOpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiLlmClient.class);

    private final RestClient http;
    private final TokenCredential credential;
    private final AiProperties properties;

    public AzureOpenAiLlmClient(RestClient http, TokenCredential credential, AiProperties properties) {
        this.http = http;
        this.credential = credential;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "azure";
    }

    /**
     * Whether a token can be obtained right now.
     *
     * <p>Asked so the dashboard can hide an action it cannot honour. A credential problem is much
     * more likely than an unreachable endpoint — a missing role assignment, an expired secret — and
     * both present as a failure only at the moment somebody tries to use the feature.
     */
    @Override
    public boolean available() {
        try {
            return token() != null;
        } catch (RuntimeException e) {
            log.warn("Azure OpenAI credential is not usable: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Map<String, Object> answer =
                http.post()
                        .uri(
                                "%s/openai/deployments/%s/chat/completions?api-version=%s"
                                        .formatted(
                                                trimmedEndpoint(), properties.model(), properties.apiVersion()))
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body(request))
                        .retrieve()
                        .body(RESPONSE);

        return new ChatResponse(contentOf(answer), modelOf(answer));
    }

    // --- request -----------------------------------------------------------

    /** The OpenAI chat-completions request body. */
    private Map<String, Object> body(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();

        // The deployment already names the model in the URL, and Azure ignores this field; it is
        // sent so the same code works against a plain OpenAI-compatible server.
        body.put("model", properties.model());
        body.put("temperature", request.temperature());
        body.put("messages", messages(request.messages()));

        responseFormat(request.responseFormat()).ifPresent(format -> body.put("response_format", format));

        return body;
    }

    private List<Map<String, String>> messages(List<ChatMessage> messages) {
        List<Map<String, String>> wire = new ArrayList<>(messages.size());

        for (ChatMessage message : messages) {
            wire.add(Map.of("role", message.role().wireName(), "content", message.content()));
        }

        return wire;
    }

    /**
     * Structured outputs, when the answer is JSON and a schema was supplied.
     *
     * <p>This is the single largest lever on whether generation succeeds: a provider honouring it
     * cannot return a payload the schema rejects, which turns the repair loop into a fallback
     * rather than the main path.
     *
     * <p>XSD has no equivalent in any chat API, so an XML request declares no format and relies on
     * the schema being in the prompt — and on the validator to catch what comes back regardless.
     */
    private Optional<Map<String, Object>> responseFormat(ResponseFormat format) {
        if (format == null || format.kind() != ResponseFormat.Kind.JSON_SCHEMA || format.schema() == null) {
            return Optional.empty();
        }

        return Optional.of(
                Map.of(
                        "type",
                        "json_schema",
                        "json_schema",
                        Map.of("name", "payload", "strict", false, "schema", format.schema())));
    }

    // --- response ----------------------------------------------------------

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE =
            new ParameterizedTypeReference<>() {};

    /**
     * The first choice's message content.
     *
     * <p>Absence is answered with empty text rather than an exception: the caller validates
     * whatever comes back, and an empty payload fails validation with a message an author can read,
     * which is more use than a parser's complaint about a field they have never heard of.
     */
    @SuppressWarnings("unchecked")
    private String contentOf(Map<String, Object> answer) {
        if (answer == null) {
            return "";
        }

        Object choices = answer.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return "";
        }

        if (!(list.getFirst() instanceof Map<?, ?> choice)) {
            return "";
        }

        if (!(((Map<String, Object>) choice).get("message") instanceof Map<?, ?> message)) {
            return "";
        }

        Object content = ((Map<String, Object>) message).get("content");
        return content instanceof String text ? text : "";
    }

    /** What actually answered, as reported — a deployment can route somewhere else. */
    private String modelOf(Map<String, Object> answer) {
        Object model = answer == null ? null : answer.get("model");
        return model instanceof String named ? named : properties.model();
    }

    // --- plumbing ----------------------------------------------------------

    private String token() {
        return credential
                .getToken(new TokenRequestContext().addScopes(properties.scope()))
                .block()
                .getToken();
    }

    /** A configured endpoint with a trailing slash would otherwise build a doubled path. */
    private String trimmedEndpoint() {
        String endpoint = properties.endpoint();
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
