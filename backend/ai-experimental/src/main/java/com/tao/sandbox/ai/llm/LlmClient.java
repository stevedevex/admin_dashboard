package com.tao.sandbox.ai.llm;

/**
 * The seam every model provider sits behind.
 *
 * <p>One method, in the OpenAI chat-completions shape. {@link FakeLlmClient} satisfies it offline
 * today; an Azure OpenAI client will satisfy it later, authenticating through
 * {@code DefaultAzureCredential} so a service principal locally and a user-assigned managed
 * identity in Azure are the same code path with different environment.
 *
 * <p>Nothing above this interface knows which implementation is active, with one deliberate
 * exception: {@link #name()} is reported all the way to the dashboard, because a demo that lets
 * offline placeholder data pass for model output is worse than one that generates nothing.
 */
public interface LlmClient {

    ChatResponse complete(ChatRequest request);

    /** Short identifier for the provider — {@code fake}, {@code azure}. Surfaced to the user. */
    String name();

    /**
     * Whether calls can actually be made right now.
     *
     * <p>Separate from the bean existing: a provider configured with an endpoint it cannot reach
     * should say so, so the dashboard can hide the action instead of offering a button that fails.
     */
    default boolean available() {
        return true;
    }
}
