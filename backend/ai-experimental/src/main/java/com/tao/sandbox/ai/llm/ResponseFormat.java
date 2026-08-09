package com.tao.sandbox.ai.llm;

/**
 * The shape the answer must take, carried beside the prompt rather than only inside it.
 *
 * <p>For JSON this maps directly onto OpenAI structured outputs ({@code response_format} with a
 * {@code json_schema}), which is the single largest lever on whether generation succeeds: a
 * provider that honours it cannot return a payload the schema rejects, so the repair loop becomes
 * a fallback instead of the main path.
 *
 * <p>XSD has no equivalent in any chat API. {@link Kind#XML_SCHEMA} therefore declares no format
 * on the wire — a client is expected to ignore it — and an XML request relies on the schema being
 * in the prompt and on the validator to judge what comes back. The kind is still carried so that
 * decision is made once, by whoever builds the request, rather than inferred in every client.
 *
 * @param schema the contract text — a JSON Schema, or the merged XSD for the response element
 */
public record ResponseFormat(Kind kind, String schema) {

    public enum Kind {
        JSON_SCHEMA,
        XML_SCHEMA
    }

    public static ResponseFormat json(String schema) {
        return new ResponseFormat(Kind.JSON_SCHEMA, schema);
    }

    public static ResponseFormat xml(String schema) {
        return new ResponseFormat(Kind.XML_SCHEMA, schema);
    }
}
