package com.tao.sandbox.ai;

/**
 * Everything a model needs to answer with a payload for one operation.
 *
 * <p>The interesting part is what is *not* here: no file name, no scenario, nothing about where
 * the answer will be stored. Generation produces a payload for an operation's contract; deciding
 * which file it belongs in is the authoring flow's job, and keeping them apart is why the same
 * generated body can be dropped into a new mock or over an existing one.
 *
 * <p>The schema is supplied by the sandbox, not by the user, and the result is checked against
 * that same schema before anyone is offered the chance to keep it.
 *
 * @param serviceId which service the payload is for
 * @param operationId which operation, since operations within a service differ in shape
 * @param format the payload format, which decides how the result is validated
 * @param schema the response schema for that operation — an OpenAPI schema or an XSD
 * @param starter an empty payload already shaped like the response, when one could be built. Given
 *     to the model as an example so the answer comes back in the right shape, which matters most
 *     for XML: there is no structured-output mode for XSD, so the shape has to be shown.
 * @param current the payload the editor already holds, or null. Context, never an instruction:
 *     a request made against an existing payload is usually an adjustment to it, and only the
 *     prompt says whether this one is — so the model is given both and decides.
 * @param prompt what the user asked for, e.g. "a corporate customer with three active accounts"
 */
public record PayloadGenerationRequest(
        String serviceId,
        String operationId,
        Format format,
        String schema,
        String starter,
        String current,
        String prompt) {

    public enum Format {
        JSON,
        XML
    }
}
