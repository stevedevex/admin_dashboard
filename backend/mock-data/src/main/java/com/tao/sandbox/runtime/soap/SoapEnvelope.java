package com.tao.sandbox.runtime.soap;

import javax.xml.namespace.QName;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Envelope reading and writing, for whichever SOAP version arrived.
 *
 * <p>Mock files hold the payload, not the envelope: the schemas describe the payload, so
 * validation lines up, files stay readable, and an envelope change touches one wrapper rather than
 * every file. It is also what lets the same mock serve a 1.1 and a 1.2 client.
 */
public final class SoapEnvelope {

    private SoapEnvelope() {}

    /** The root element of the body — what identifies the operation on the wire. */
    public static QName bodyElement(Document envelope, SoapVersion version) {
        NodeList bodies = envelope.getElementsByTagNameNS(version.envelopeNamespace(), "Body");
        if (bodies.getLength() == 0) {
            throw new IllegalArgumentException("No Body element in the request envelope");
        }

        NodeList children = bodies.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                return new QName(
                        element.getNamespaceURI() == null ? "" : element.getNamespaceURI(),
                        element.getLocalName());
            }
        }

        throw new IllegalArgumentException("Body is empty");
    }

    /**
     * @param envelopeHeader header content, or null for none. An empty {@code <Header/>} is
     *     deliberately never emitted: some stacks treat a present-but-empty header differently
     *     from an absent one, so "no header" must stay distinguishable.
     */
    public static String wrap(String payload, SoapVersion version, String envelopeHeader) {
        String header =
                envelopeHeader == null || envelopeHeader.isBlank()
                        ? ""
                        : """
                          <soapenv:Header>
                        %s
                          </soapenv:Header>
                        """
                                .formatted(indent(envelopeHeader));

        return """
            <soapenv:Envelope xmlns:soapenv="%s">
            %s  <soapenv:Body>
            %s
              </soapenv:Body>
            </soapenv:Envelope>
            """
                .formatted(version.envelopeNamespace(), header, indent(payload));
    }

    /**
     * A fault, not an empty body.
     *
     * <p>Returning a well-formed but empty response on a miss would reproduce exactly the upstream
     * behaviour this service exists to eliminate, and would do it invisibly. The detail carries the
     * whole resolution trace so the reader knows which file to create.
     */
    public static String fault(SoapVersion version, String code, String message, String detail) {
        return version == SoapVersion.SOAP_1_1
                ? fault11(version, code, message, detail)
                : fault12(version, code, message, detail);
    }

    private static String fault11(SoapVersion version, String code, String message, String detail) {
        return """
            <soapenv:Envelope xmlns:soapenv="%s">
              <soapenv:Body>
                <soapenv:Fault>
                  <faultcode>soapenv:%s</faultcode>
                  <faultstring>%s</faultstring>
                  <detail>
                    <taoSandbox><![CDATA[%s]]></taoSandbox>
                  </detail>
                </soapenv:Fault>
              </soapenv:Body>
            </soapenv:Envelope>
            """
                .formatted(version.envelopeNamespace(), code, escape(message), detail);
    }

    private static String fault12(SoapVersion version, String code, String message, String detail) {
        return """
            <soapenv:Envelope xmlns:soapenv="%s">
              <soapenv:Body>
                <soapenv:Fault>
                  <soapenv:Code>
                    <soapenv:Value>soapenv:%s</soapenv:Value>
                  </soapenv:Code>
                  <soapenv:Reason>
                    <soapenv:Text xml:lang="en">%s</soapenv:Text>
                  </soapenv:Reason>
                  <soapenv:Detail>
                    <taoSandbox><![CDATA[%s]]></taoSandbox>
                  </soapenv:Detail>
                </soapenv:Fault>
              </soapenv:Body>
            </soapenv:Envelope>
            """
                .formatted(version.envelopeNamespace(), code, escape(message), detail);
    }

    private static String indent(String payload) {
        return payload.strip().lines().map(line -> "    " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
