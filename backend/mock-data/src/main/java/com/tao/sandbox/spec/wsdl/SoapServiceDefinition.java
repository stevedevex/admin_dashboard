package com.tao.sandbox.spec.wsdl;

import java.util.Map;
import javax.xml.namespace.QName;

/**
 * A SOAP service the sandbox stands in for.
 *
 * @param elementToOperation body root element → operation name. This mapping is the reason the
 *     WSDL must be parsed at all: in document/literal style the body's root element is the input
 *     message's element (e.g. {@code TradePriceRequest}), not the operation name (e.g. {@code
 *     GetLastTradePrice}). Guessing one from the other does not work.
 * @param served operations named in configuration; anything else answers NOT_IMPLEMENTED
 * @param originalAddress the endpoint the real service publishes, replaced when the WSDL is served
 * @param imports every document the WSDL reaches by {@code <xsd:import>}, {@code <xsd:include>} or
 *     {@code <wsdl:import>}, keyed by the reference exactly as written, gathered transitively — a
 *     file this imports is walked too, not just the WSDL's own direct references
 * @param defaultResponseHeader envelope header for every response, unless a mock overrides it
 * @param schemas the XSD taken out of this WSDL, and each operation's response element
 */
public record SoapServiceDefinition(
        String serviceId,
        String path,
        String wsdl,
        String targetNamespace,
        String originalAddress,
        Map<QName, String> elementToOperation,
        Map<String, SoapOperationDefinition> served,
        Map<String, String> namespaces,
        Map<String, String> imports,
        String defaultResponseHeader,
        SoapSchemas schemas) {

    /**
     * The WSDL as a client should see it: the endpoint replaced with this server, and every
     * imported schema location pointed back here too.
     *
     * <p>Rewriting the address alone is not enough — a client that fetches the WSDL will follow
     * its imports next, and an unrewritten import sends it back to the real host.
     */
    public String wsdlServedFrom(String endpoint) {
        String rewritten = originalAddress == null ? wsdl : wsdl.replace(originalAddress, endpoint);

        for (String name : imports.keySet()) {
            rewritten = rewritten.replace("\"" + name + "\"", "\"" + endpoint + "?xsd=" + name + "\"");
        }

        return rewritten;
    }
}
