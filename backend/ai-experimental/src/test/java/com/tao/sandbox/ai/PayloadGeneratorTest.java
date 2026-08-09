package com.tao.sandbox.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tao.sandbox.ai.llm.ChatRequest;
import com.tao.sandbox.ai.llm.ChatResponse;
import com.tao.sandbox.ai.llm.LlmClient;
import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.ServiceDescriptor;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.validate.MockValidator;
import com.tao.sandbox.validate.Validation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The loop, not the model.
 *
 * <p>What matters here is what the sandbox does with an answer once it has one: check it, ask
 * again when the contract was broken, and never claim a verdict the validator did not give. A
 * scripted client stands in for the provider so each of those can be provoked deliberately.
 */
class PayloadGeneratorTest {

    /** Answers a queued script in order, and records what it was asked. */
    private static final class ScriptedClient implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        private final List<ChatRequest> seen = new ArrayList<>();

        void willAnswer(String... bodies) {
            answers.addAll(List.of(bodies));
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            seen.add(request);
            return new ChatResponse(answers.isEmpty() ? "" : answers.removeFirst(), "scripted");
        }

        @Override
        public String name() {
            return "scripted";
        }
    }

    private static final Validation VALID = new Validation(true, Validation.Checked.SCHEMA, 100, List.of());

    private static final Validation INVALID =
            new Validation(
                    false,
                    Validation.Checked.SCHEMA,
                    50,
                    List.of(new Validation.Issue("/0/id", 1, "'x' is not a valid integer", "cvc-datatype")));

    private SpecRegistry registry;
    private MockValidator validator;
    private ScriptedClient client;
    private PayloadGenerator generator;

    @BeforeEach
    void setUp() {
        registry = mock(SpecRegistry.class);
        validator = mock(MockValidator.class);
        client = new ScriptedClient();
        generator = new PayloadGenerator(registry, validator, client, AiProperties.defaults());

        when(registry.findService("petstore"))
                .thenReturn(Optional.of(new ServiceDescriptor("petstore", "Pet Store", ServiceType.REST, "/p", "spec", List.of())));
        when(registry.findResponseSchema("petstore", "listPets")).thenReturn(Optional.of("{\"type\":\"array\"}"));
    }

    @Test
    void returnsTheFirstAnswerWhenItSatisfiesTheContract() {
        client.willAnswer("[]");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(VALID);

        PayloadGeneration result = generator.generate("petstore", "listPets", "some pets", null);

        assertThat(result.body()).isEqualTo("[]");
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.generator()).isEqualTo("scripted");
    }

    @Test
    void asksAgainWhenTheContractWasBroken() {
        client.willAnswer("[{\"id\":\"x\"}]", "[{\"id\":1}]");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(INVALID).thenReturn(VALID);

        PayloadGeneration result = generator.generate("petstore", "listPets", "some pets", null);

        assertThat(result.body()).isEqualTo("[{\"id\":1}]");
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    void handsTheValidatorsOwnComplaintBackToBeFixed() {
        client.willAnswer("[{\"id\":\"x\"}]", "[{\"id\":1}]");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(INVALID).thenReturn(VALID);

        generator.generate("petstore", "listPets", "some pets", null);

        String repair = client.seen.get(1).messages().getLast().content();
        assertThat(repair).contains("/0/id").contains("'x' is not a valid integer");
    }

    @Test
    void reportsFailureHonestlyRatherThanLoopingForever() {
        client.willAnswer("nonsense", "still nonsense");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(INVALID);

        PayloadGeneration result = generator.generate("petstore", "listPets", "some pets", null);

        // Two calls and no more: a model told exactly what was wrong and still wrong is not going
        // to be argued into correctness, and the author can read the issues.
        assertThat(client.seen).hasSize(2);
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().issues()).isNotEmpty();
    }

    @Test
    void keepsTheUserPromptOutOfTheSchemaContext() {
        client.willAnswer("[]");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(VALID);

        generator.generate("petstore", "listPets", "10 pets", null);

        // The final user turn carries the request alone. Anything reading it for intent — a count,
        // most obviously — must not be reading the schema along with it.
        assertThat(client.seen.getFirst().messages().getLast().content()).isEqualTo("10 pets");
    }

    @Test
    void stripsTheCodeFenceModelsAddAnyway() {
        client.willAnswer("```json\n[]\n```");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(VALID);

        assertThat(generator.generate("petstore", "listPets", null, null).body()).isEqualTo("[]");
    }

    @Test
    void sendsWhatTheEditorHoldsAsContext() {
        client.willAnswer("[]");
        when(validator.validate(anyString(), anyString(), any())).thenReturn(VALID);

        generator.generate("petstore", "listPets", "make three of them cats", "[{\"id\":1}]");

        // Carried as context, not as the request: an adjustment must be able to stay one, and the
        // prompt is still the only thing that says whether this is an adjustment at all.
        String context =
                client.seen.getFirst().messages().stream()
                        .map(message -> message.content())
                        .reduce("", (a, b) -> a + "\n" + b);

        assertThat(context).contains("[{\"id\":1}]");
        assertThat(client.seen.getFirst().messages().getLast().content())
                .isEqualTo("make three of them cats");
    }

    @Test
    void refusesAnOperationWithNoContractToGenerateAgainst() {
        when(registry.findResponseSchema("petstore", "createPets")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> generator.generate("petstore", "createPets", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no response schema");
    }

    @Test
    void refusesAServiceItDoesNotServe() {
        when(registry.findService("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> generator.generate("nope", "whatever", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such service");
    }
}
