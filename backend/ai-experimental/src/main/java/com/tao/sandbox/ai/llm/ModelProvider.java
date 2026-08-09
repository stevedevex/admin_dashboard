package com.tao.sandbox.ai.llm;

/**
 * What is behind the {@code ChatModel}, and whether it can be called right now.
 *
 * <p>The two questions Spring AI does not answer. Its {@code ChatModel} is a way to call a model,
 * not a way to ask about one: a misconfigured provider is discovered by calling it and failing,
 * which is exactly what the control panel exists to avoid — it disables an action rather than
 * offering a button that throws.
 *
 * <p>{@link #name()} is reported all the way to the dashboard beside any payload a model produced,
 * because nobody can tell generated text from authored text by reading it, and which one made a
 * given payload is the fact a reader most needs and least can recover.
 *
 * <p>Everything else about talking to a model — messages, options, responses — is Spring AI's and
 * is not restated here. This interface exists only for what it genuinely lacks.
 */
public interface ModelProvider {

    /** Short identifier for the provider, surfaced to the user — {@code azure}. */
    String name();

    /**
     * Whether a call could succeed right now.
     *
     * <p>Separate from the bean existing. A credential problem is far more likely than an
     * unreachable endpoint — a missing role assignment, an expired secret — and both present only
     * at the moment somebody tries to use the feature unless something asks first.
     */
    boolean available();
}
