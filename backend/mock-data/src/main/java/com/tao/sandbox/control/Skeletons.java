package com.tao.sandbox.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An empty payload shaped like the contract's declared response.
 *
 * <p>Offered as a starting point, never as a mock. A skeleton served as-is would answer with a
 * well-formed body containing nothing, which is the upstream behaviour this sandbox exists to
 * eliminate — so it is handed to an author to fill in, and the {@code unchecked} state it saves
 * under says plainly that nobody has vouched for it yet. It saves the typing, not the thinking.
 *
 * <p>Uses Jackson 2, like {@link com.tao.sandbox.validate.MockValidator}, because the schema text
 * it reads came from the same swagger-parser model.
 */
final class Skeletons {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Deep enough for any response a person reads on screen; stops a self-referencing schema. */
    private static final int MAX_DEPTH = 10;

    private Skeletons() {}

    /**
     * @param schemaText the operation's response JSON Schema, as the registry holds it
     * @return the skeleton as pretty JSON, or null when the schema describes nothing to build
     */
    static String fromJsonSchema(String schemaText) {
        if (schemaText == null || schemaText.isBlank()) {
            return null;
        }

        try {
            JsonNode instance = build(JSON.readTree(schemaText), 0);
            return instance == null ? null : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(instance);
        } catch (Exception e) {
            // The schema came out of a document parsed at startup, so this should not happen — and
            // an author with no skeleton is merely inconvenienced, while one handed a malformed
            // payload is misled.
            return null;
        }
    }

    private static JsonNode build(JsonNode schema, int depth) {
        if (schema == null || !schema.isObject() || depth > MAX_DEPTH) {
            return null;
        }

        String type = typeOf(schema);

        if ("array".equals(type)) {
            ArrayNode array = JSON.createArrayNode();
            // Exactly one element: it shows the shape, and a list of empty items shows it no better.
            JsonNode item = build(schema.get("items"), depth + 1);
            if (item != null) {
                array.add(item);
            }
            return array;
        }

        JsonNode properties = schema.get("properties");
        if (properties instanceof ObjectNode declared && !declared.isEmpty()) {
            ObjectNode object = JSON.createObjectNode();
            declared
                    .properties()
                    .forEach(
                            property -> {
                                JsonNode value = build(property.getValue(), depth + 1);
                                object.set(
                                        property.getKey(),
                                        value != null ? value : placeholder(property.getValue()));
                            });
            return object;
        }

        return "object".equals(type) ? JSON.createObjectNode() : null;
    }

    /**
     * A value of the declared type with nothing in it.
     *
     * <p>Empty rather than invented: a plausible-looking number is a value someone has to notice
     * and replace, and the ones nobody notices are how a mock ends up asserting something the
     * author never meant.
     */
    private static JsonNode placeholder(JsonNode schema) {
        return switch (typeOf(schema)) {
            case "integer", "number" -> JSON.getNodeFactory().numberNode(0);
            case "boolean" -> JSON.getNodeFactory().booleanNode(false);
            case "array" -> JSON.createArrayNode();
            case "object" -> JSON.createObjectNode();
            default -> JSON.getNodeFactory().textNode("");
        };
    }

    /** OpenAPI 3.1 may write a union — {@code ["string", "null"]} — where 3.0 writes a string. */
    private static String typeOf(JsonNode schema) {
        if (schema == null) {
            return "";
        }

        JsonNode type = schema.get("type");
        if (type == null) {
            return "";
        }
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (candidate.isTextual() && !"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        return "";
    }
}
