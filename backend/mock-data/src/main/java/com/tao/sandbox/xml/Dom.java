package com.tao.sandbox.xml;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The DOM traversals this codebase actually uses, written once.
 *
 * <p>DOM's own API answers these questions badly — {@code getChildNodes} mixes text and comment
 * nodes in with elements, and QName resolution needs the in-scope namespace bindings — so every
 * class walking a WSDL or schema had grown its own copy of the same four helpers.
 */
public final class Dom {

    private Dom() {}

    /** Direct element children of {@code parent} matching namespace and local name. */
    public static List<Element> children(Node parent, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element
                    && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                found.add(element);
            }
        }
        return found;
    }

    /** All direct element children of {@code parent}, whatever their name. */
    public static List<Element> elementChildren(Node parent) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                found.add(element);
            }
        }
        return found;
    }

    /** {@code tns:Thing} → {@code Thing}; an unprefixed name is returned unchanged. */
    public static String localPart(String prefixed) {
        int colon = prefixed.indexOf(':');
        return colon < 0 ? prefixed : prefixed.substring(colon + 1);
    }

    /**
     * Resolves a prefixed name against the namespace bindings in scope at {@code scope}. An
     * unresolvable prefix yields the empty namespace rather than an error — the reference is then
     * simply never matched, which the caller reports in its own vocabulary.
     */
    public static QName qnameOf(String prefixed, Element scope) {
        int colon = prefixed.indexOf(':');
        if (colon < 0) {
            return new QName(prefixed);
        }
        String namespace = scope.lookupNamespaceURI(prefixed.substring(0, colon));
        return new QName(namespace == null ? "" : namespace, prefixed.substring(colon + 1));
    }
}
