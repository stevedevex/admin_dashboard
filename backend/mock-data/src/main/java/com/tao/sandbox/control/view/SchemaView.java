package com.tao.sandbox.control.view;

/**
 * The schema for one operation's response, for validation display and later for generation.
 *
 * @param available false with a null schema is a normal answer, not an error — some contracts
 *     declare no response body, and the dashboard must render that as a state rather than as a
 *     failure. Reporting it as an error would make every schema-less operation look broken.
 * @param reason why there is none, when there is none. An absence with no explanation reads as a
 *     defect; the actual causes — an RPC-style binding, a contract that declares no response body —
 *     are things the reader can act on or dismiss.
 */
public record SchemaView(String format, boolean available, String schema, String reason) {

    public static SchemaView json(String schema) {
        return schema != null
                ? new SchemaView("JSON", true, schema, null)
                : new SchemaView("JSON", false, null, "The contract declares no response body for this operation");
    }

    /** A SOAP operation, checked against the XSD taken out of its WSDL. */
    public static SchemaView xsd(String schema, String reason) {
        return schema != null
                ? new SchemaView("XSD", true, schema, null)
                : new SchemaView("XSD", false, null, reason);
    }
}
