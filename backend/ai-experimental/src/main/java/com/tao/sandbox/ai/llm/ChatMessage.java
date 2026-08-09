package com.tao.sandbox.ai.llm;

/**
 * One turn of a chat completion, in the shape every OpenAI-compatible API uses.
 *
 * @param role who is speaking. {@code SYSTEM} carries the standing instruction, {@code USER} what
 *     was asked for, {@code ASSISTANT} a previous answer being corrected — which is how the repair
 *     turn hands a rejected payload back with the validator's complaints attached.
 */
public record ChatMessage(Role role, String content) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT;

        /** The wire spelling: these APIs name roles in lower case. */
        public String wireName() {
            return name().toLowerCase();
        }
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}
