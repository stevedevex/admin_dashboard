package com.tao.sandbox.xml;

import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
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

    /**
     * A document back to text, for a person to read.
     *
     * <p>Indented and without an XML declaration, because the only thing serialised here is a draft
     * request offered to somebody in an editor. Nothing on the serving path serialises a DOM — mock
     * payloads are stored and returned as the bytes they were written as, which is why this is the
     * one place that needs it.
     */
    public static String serialize(Document document) {
        try {
            // The indenter adds layout; it does not replace what is already there. Serialising a
            // document parsed from indented text without this leaves the original whitespace nodes
            // in place and indents around them, producing a blank line between every element.
            stripBlankText(document.getDocumentElement());

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString().trim();
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the document: " + e.getMessage(), e);
        }
    }

    /**
     * Removes the whitespace between elements, and only that.
     *
     * <p>Whitespace inside an element that has text of its own is content — {@code <name> Rex
     * </name>} is not {@code <name>Rex</name>} to every consumer — so a node is only dropped where
     * its parent has element children, which is where it can only have been layout.
     */
    private static void stripBlankText(Node node) {
        if (node == null) {
            return;
        }

        boolean hasElements = false;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                hasElements = true;
                break;
            }
        }

        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();

            if (hasElements && child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripBlankText(child);
            }

            child = next;
        }
    }
}
