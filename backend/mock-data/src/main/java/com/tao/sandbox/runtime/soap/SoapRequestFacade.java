package com.tao.sandbox.runtime.soap;

import com.tao.sandbox.runtime.match.RequestFacade;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.http.HttpHeaders;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads a SOAP envelope for key extraction. */
public class SoapRequestFacade implements RequestFacade {

    private final Document envelope;
    private final HttpHeaders headers;
    private final SoapVersion version;
    private final XPath xpath;

    public SoapRequestFacade(
            Document envelope, HttpHeaders headers, Map<String, String> namespaces, SoapVersion version) {
        this.envelope = envelope;
        this.headers = headers;
        this.version = version;
        // newDefaultInstance, not newInstance: the latter runs a ServiceLoader classpath scan on
        // every call, which is real money on the hot path. A fresh factory per request keeps
        // thread-safety trivial — neither XPathFactory nor XPath is safe to share.
        this.xpath = XPathFactory.newDefaultInstance().newXPath();
        this.xpath.setNamespaceContext(context(namespaces, version));
    }

    @Override
    public Optional<String> path(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<String> query(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.getFirst(name));
    }

    @Override
    public Optional<String> body(String expression) {
        return Optional.empty();
    }

    @Override
    public Optional<String> xpath(String expression) {
        try {
            Node node = (Node) xpath.evaluate(expression, envelope, XPathConstants.NODE);
            if (node == null) {
                return Optional.empty();
            }
            String text = node.getTextContent();
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (Exception e) {
            // A path that matches nothing is a miss, not a failure. The trace shows the key was
            // not extracted, which points at the expression far better than a 500 would.
            return Optional.empty();
        }
    }

    /**
     * The element names the envelope carries, from the header and from the operation's payload.
     *
     * <p>Local names without their namespaces, because this is read by a person comparing what they
     * sent against what was extracted, not by anything that resolves them.
     */
    @Override
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>();
        collectChildren(envelope.getElementsByTagNameNS(version.envelopeNamespace(), "Header"), names, false);
        collectChildren(envelope.getElementsByTagNameNS(version.envelopeNamespace(), "Body"), names, true);
        return names;
    }

    /**
     * @param descend the Body's own child is the operation element, so its children are the fields;
     *     a Header's children are the fields themselves
     */
    private void collectChildren(NodeList sections, List<String> names, boolean descend) {
        if (sections.getLength() == 0) {
            return;
        }

        for (Element child : elementsOf(sections.item(0))) {
            if (descend) {
                elementsOf(child).forEach(field -> names.add(field.getLocalName()));
            } else {
                names.add(child.getLocalName());
            }
        }
    }

    private List<Element> elementsOf(Node parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /**
     * XPath 1.0 cannot address elements in a default namespace at all — an unprefixed name means
     * "no namespace", never "the document's default". Every service therefore binds its own
     * prefixes in configuration, and {@code soapenv} is bound here so nobody has to declare it.
     */
    private NamespaceContext context(Map<String, String> configured, SoapVersion version) {
        Map<String, String> bindings = new java.util.LinkedHashMap<>(configured);
        // Bound to whichever version arrived, so a single configured XPath such as
        // /soapenv:Envelope/soapenv:Body/... serves 1.1 and 1.2 clients alike.
        bindings.put("soapenv", version.envelopeNamespace());

        return new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return bindings.getOrDefault(prefix, javax.xml.XMLConstants.NULL_NS_URI);
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return bindings.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(namespaceURI))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                return bindings.keySet().iterator();
            }
        };
    }
}
