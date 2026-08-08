package com.tao.sandbox.ai;

/**
 * What generating a mock payload will need. Placeholder — nothing consumes this yet.
 *
 * <p>Recorded now because the shape is the interesting part: the schema is supplied by the
 * sandbox, not by the user, and the result is checked against that same schema before anyone is
 * offered the chance to keep it.
 *
 * @param serviceId which service the payload is for
 * @param operationId which operation, since operations within a service differ in shape
 * @param format the payload format, which decides how the result is validated
 * @param schema the response schema for that operation — an OpenAPI schema or an XSD
 * @param prompt what the user asked for, e.g. "a corporate customer with three active accounts"
 */
public record PayloadGenerationRequest(
        String serviceId, String operationId, Format format, String schema, String prompt) {

    public enum Format {
        JSON,
        XML
    }
}
