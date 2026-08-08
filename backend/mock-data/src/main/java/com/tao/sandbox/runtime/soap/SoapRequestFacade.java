package com.tao.sandbox.runtime.soap;

import com.tao.sandbox.runtime.match.RequestFacade;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.http.HttpHeaders;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/** Reads a SOAP envelope for key extraction. */
public class SoapRequestFacade implements RequestFacade {

    private final Document envelope;
    private final HttpHeaders headers;
    private final XPath xpath;

    public SoapRequestFacade(
            Document envelope, HttpHeaders headers, Map<String, String> namespaces, SoapVersion version) {
        this.envelope = envelope;
        this.headers = headers;
        this.xpath = XPathFactory.newInstance().newXPath();
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
