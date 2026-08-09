package com.tao.sandbox.ai.llm;

/**
 * What came back.
 *
 * @param content the assistant's message, expected to be the payload and nothing else. Whether it
 *     actually is remains the caller's problem — see {@code PayloadGenerator}, which strips the
 *     code fences models add regardless of instruction, and then lets the validator decide.
 * @param model which model answered, as the provider reports it rather than as it was asked for.
 *     A deployment can route elsewhere, and a payload is worth less if nobody can say what made it.
 */
public record ChatResponse(String content, String model) {}
