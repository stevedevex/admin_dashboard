package com.tao.sandbox.ai.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * One call to a chat completion API.
 *
 * <p>Deliberately the OpenAI request shape. Azure OpenAI speaks it, every local runner speaks it,
 * and keeping to it means the provider swap is a transport change rather than a redesign of
 * everything upstream.
 *
 * @param temperature low for this use. Payload generation wants plausible and schema-shaped, not
 *     inventive — creativity here shows up as fields the contract never declared.
 */
public record ChatRequest(
        String model, List<ChatMessage> messages, double temperature, ResponseFormat responseFormat) {

    public ChatRequest {
        messages = List.copyOf(messages);
    }

    /** The same exchange with one more turn, for handing a rejected payload back to be corrected. */
    public ChatRequest continuedWith(ChatMessage... further) {
        List<ChatMessage> extended = new ArrayList<>(messages);
        extended.addAll(List.of(further));
        return new ChatRequest(model, extended, temperature, responseFormat);
    }
}
