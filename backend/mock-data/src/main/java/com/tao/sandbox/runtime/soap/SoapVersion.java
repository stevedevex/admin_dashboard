package com.tao.sandbox.runtime.soap;

import java.util.Optional;
import org.w3c.dom.Document;

/**
 * The differences between SOAP 1.1 and 1.2, in one place.
 *
 * <p>They differ in more than the envelope namespace: the media type changes, and the fault body
 * is restructured entirely — {@code faultcode}/{@code faultstring} become {@code Code/Value} and
 * {@code Reason/Text}. A 1.2 client handed a 1.1 fault cannot unmarshal it.
 *
 * <p>The version is always taken from the request and echoed back, so one configuration serves
 * both kinds of client without either knowing the other exists.
 */
public enum SoapVersion {
    SOAP_1_1("http://schemas.xmlsoap.org/soap/envelope/", "text/xml;charset=UTF-8"),
    SOAP_1_2("http://www.w3.org/2003/05/soap-envelope", "application/soap+xml;charset=UTF-8");

    private final String envelopeNamespace;
    private final String contentType;

    SoapVersion(String envelopeNamespace, String contentType) {
        this.envelopeNamespace = envelopeNamespace;
        this.contentType = contentType;
    }

    public String envelopeNamespace() {
        return envelopeNamespace;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<SoapVersion> of(Document envelope) {
        String namespace = envelope.getDocumentElement().getNamespaceURI();
        for (SoapVersion version : values()) {
            if (version.envelopeNamespace.equals(namespace)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }

    /** Fault code for a problem with the request. */
    public String senderCode() {
        return this == SOAP_1_1 ? "Client" : "Sender";
    }

    /** Fault code for a problem on this side. */
    public String receiverCode() {
        return this == SOAP_1_1 ? "Server" : "Receiver";
    }

    /**
     * SOAP 1.2 distinguishes the two at the HTTP layer; 1.1 always uses 500.
     *
     * <p>It matters because clients retry a 500 and do not retry a 400, so getting it wrong makes
     * a malformed request look like a flaky server.
     */
    public int httpStatusFor(String faultCode) {
        if (this == SOAP_1_1) {
            return 500;
        }
        return faultCode.equals(senderCode()) ? 400 : 500;
    }
}
