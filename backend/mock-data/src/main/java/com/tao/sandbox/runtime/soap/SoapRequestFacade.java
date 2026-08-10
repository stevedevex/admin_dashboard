package com.tao.sandbox.runtime.soap;

import com.tao.sandbox.runtime.match.RequestFacade;
import com.tao.sandbox.xml.Dom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
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

    /** The same bound the schema walkers use: far past any envelope a person reads on screen. */
    private static final int MAX_DEPTH = 10;

    /**
     * The fields the envelope carries, from the header and from the operation's payload, nested ones
     * as dotted paths.
     *
     * <p>Local names without their namespaces, because this is read by a person comparing what they
     * sent against what was extracted, not by anything that resolves them. {@link
     * KeySpec#fieldPath()} strips prefixes from the other side for the same reason.
     *
     * <p>Anchored below the envelope scaffolding — at the operation element for a body field, at the
     * header's own children for a header one — so {@code …/x:Request/x:Party/x:Id} and the {@code
     * Party.Id} reported here are the same path written twice.
     *
     * <p>This listed one level only, which meant a key reading {@code …/x:Party/x:Id} had {@code
     * Party} as the only name available, and {@code Party} matched no key — so the field that
     * decided the answer was reported as ignored.
     */
    @Override
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>();

        // A Header's children are the fields themselves; the Body's own child is the operation
        // element, which is not a field — its children are.
        for (Element field : sectionChildren("Header")) {
            collect(field, "", names, 0);
        }
        for (Element operation : sectionChildren("Body")) {
            for (Element field : Dom.elementChildren(operation)) {
                collect(field, "", names, 0);
            }
        }

        return names;
    }

    private List<Element> sectionChildren(String localName) {
        NodeList sections = envelope.getElementsByTagNameNS(version.envelopeNamespace(), localName);
        return sections.getLength() == 0 ? List.of() : Dom.elementChildren(sections.item(0));
    }

    private void collect(Element element, String prefix, List<String> names, int depth) {
        String path = prefix.isEmpty() ? element.getLocalName() : prefix + "." + element.getLocalName();
        List<Element> children = Dom.elementChildren(element);

        if (children.isEmpty() || depth >= MAX_DEPTH) {
            names.add(path);
            return;
        }

        for (Element child : children) {
            collect(child, path, names, depth + 1);
        }
    }


    /**
     * XPath 1.0 cannot address elements in a default namespace at all — an unprefixed name means
     * "no namespace", never "the document's default". Every service therefore binds its own
     * prefixes in configuration, and {@code soapenv} is bound here so nobody has to declare it.
     */
    private NamespaceContext context(Map<String, String> configured, SoapVersion version) {
        Map<String, String> bindings = new LinkedHashMap<>(configured);
        // Bound to whichever version arrived, so a single configured XPath such as
        // /soapenv:Envelope/soapenv:Body/... serves 1.1 and 1.2 clients alike.
        bindings.put("soapenv", version.envelopeNamespace());

        return new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return bindings.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
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
