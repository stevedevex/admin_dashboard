package com.tao.sandbox.ai.llm.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.tao.sandbox.ai.AiProperties;
import com.tao.sandbox.ai.llm.ChatMessage;
import com.tao.sandbox.ai.llm.ChatRequest;
import com.tao.sandbox.ai.llm.ChatResponse;
import com.tao.sandbox.ai.llm.ResponseFormat;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;

/**
 * The wire contract, against a stubbed server.
 *
 * <p>This is as far as verification goes without Azure: that the request goes to the deployment's
 * URL with a bearer token, carries the OpenAI body this code intends, and that the answer is read
 * out of the shape the API documents. Whether a real endpoint agrees is the first live call's to
 * establish — see the note on the class under test.
 */
class AzureOpenAiLlmClientTest {

    private static final AiProperties PROPERTIES =
            new AiProperties(
                    "https://example-resource.openai.azure.com",
                    "my-deployment",
                    "2024-10-21",
                    "https://cognitiveservices.azure.com/.default",
                    0.2,
                    new AiProperties.Auth(AiProperties.Auth.Mode.DEFAULT, "", "", ""));

    /** Hands back a fixed token; obtaining one for real is Azure's job, not this test's. */
    private static final TokenCredential CREDENTIAL =
            context -> Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1)));

    private MockRestServiceServer server;
    private AzureOpenAiLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AzureOpenAiLlmClient(builder.build(), CREDENTIAL, PROPERTIES);
    }

    private ChatRequest chat(ResponseFormat format) {
        return new ChatRequest(
                "my-deployment",
                List.of(ChatMessage.system("rules"), ChatMessage.user("10 records")),
                0.2,
                format);
    }

    private static final String ANSWER =
            """
            {
              "model": "gpt-4o-mini-2024-07-18",
              "choices": [ { "message": { "role": "assistant", "content": "[]" } } ]
            }
            """;

    @Test
    void callsTheDeploymentWithABearerToken() {
        server.expect(
                        requestTo(
                                "https://example-resource.openai.azure.com/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        client.complete(chat(ResponseFormat.json("{\"type\":\"array\"}")));

        server.verify();
    }

    @Test
    void sendsTheMessagesWithLowerCaseRoles() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("10 records"))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        client.complete(chat(ResponseFormat.json("{\"type\":\"array\"}")));

        server.verify();
    }

    @Test
    void asksForStructuredOutputWhenTheAnswerIsJson() {
        // The largest lever on validity: a provider honouring this cannot return a payload the
        // schema rejects.
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        client.complete(chat(ResponseFormat.json("{\"type\":\"array\"}")));

        server.verify();
    }

    @Test
    void asksForNoStructuredOutputWhenTheAnswerIsXml() {
        // No chat API constrains output to an XSD, so claiming a format here would be a promise
        // nothing keeps. The validator is what judges XML.
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        client.complete(chat(ResponseFormat.xml("<xsd/>")));

        server.verify();
    }

    @Test
    void readsTheContentAndTheModelThatAnswered() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        ChatResponse response = client.complete(chat(ResponseFormat.json("{}")));

        assertThat(response.content()).isEqualTo("[]");
        // As reported, not as asked for: a deployment can route elsewhere.
        assertThat(response.model()).isEqualTo("gpt-4o-mini-2024-07-18");
    }

    @Test
    void answersEmptyRatherThanThrowingWhenThereAreNoChoices() {
        // The caller validates whatever comes back, and an empty payload fails validation with a
        // message an author can act on — better than a parser complaining about a missing field.
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.complete(chat(ResponseFormat.json("{}"))).content()).isEmpty();
    }

    @Test
    void doesNotDoubleTheSlashWhenTheEndpointHasATrailingOne() {
        AiProperties trailing =
                new AiProperties(
                        "https://example-resource.openai.azure.com/",
                        PROPERTIES.model(),
                        PROPERTIES.apiVersion(),
                        PROPERTIES.scope(),
                        PROPERTIES.temperature(),
                        PROPERTIES.auth());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer stub = MockRestServiceServer.bindTo(builder).build();
        AzureOpenAiLlmClient trailingClient =
                new AzureOpenAiLlmClient(builder.build(), CREDENTIAL, trailing);

        stub.expect(
                        requestTo(
                                "https://example-resource.openai.azure.com/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        trailingClient.complete(chat(ResponseFormat.json("{}")));

        stub.verify();
    }
}
