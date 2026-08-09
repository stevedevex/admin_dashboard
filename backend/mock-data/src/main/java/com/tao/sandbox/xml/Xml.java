package com.tao.sandbox.xml;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * XML parsing, configured once and safely.
 *
 * <p>Inbound envelopes are untrusted input, so external entities and DTDs are disabled: a mock
 * server that parses whatever a client sends is an obvious target for entity expansion and file
 * disclosure, and the defaults do not protect against either.
 *
 * <p>Namespace awareness is mandatory rather than optional — every XPath in this service is
 * namespace-qualified, and a non-aware parser silently matches nothing.
 */
public final class Xml {

    private Xml() {}

    private static final DocumentBuilderFactory FACTORY = secureFactory();

    private static DocumentBuilderFactory secureFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Could not secure the XML parser", e);
        }
        factory.setNamespaceAware(true);
        return factory;
    }

    public static Document parse(String xml) {
        try {
            // DocumentBuilder is not thread-safe; the factory is, so build per call.
            DocumentBuilder builder = FACTORY.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not well-formed XML: " + e.getMessage(), e);
        }
    }
}
