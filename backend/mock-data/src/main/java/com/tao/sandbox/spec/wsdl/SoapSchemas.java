package com.tao.sandbox.spec.wsdl;

import com.tao.sandbox.xml.Dom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The XSD a SOAP service declares, and what each operation's response looks like against it.
 *
 * <p>Taken out of the WSDL rather than kept alongside it. The schema is already in the contract —
 * inline under {@code <wsdl:types>} or in a document it imports — so a second hand-maintained copy
 * would be duplication that drifts, and drift here is silent: validation would keep passing against
 * a schema that no longer describes what the service returns, which is worse than not validating.
 *
 * @param documentText one merged, well-formed schema per target namespace — see {@code
 *     XsdExtractor.mergeByNamespace} for why a namespace split across several files is combined
 *     into one rather than shown as whichever file happened to be read first
 * @param unavailable why there is no usable schema, or null when there is one. Never a bare
 *     absence: a service reporting "no schema" without saying why looks broken, and the usual
 *     causes — an RPC-style binding, a reference the sandbox declined to fetch — are things the
 *     reader can act on.
 */
public record SoapSchemas(
        Schema validator,
        Map<String, QName> responseElements,
        List<Document> documents,
        Map<String, String> documentText,
        String unavailable) {

    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    public static SoapSchemas none(String reason) {
        return new SoapSchemas(null, Map.of(), List.of(), Map.of(), reason);
    }

    public SoapSchemas {
        responseElements = Map.copyOf(responseElements);
        documents = List.copyOf(documents);
        documentText = Map.copyOf(documentText);
    }

    public Optional<Schema> compiled() {
        return Optional.ofNullable(validator);
    }

    /** The element a response payload for this operation must be, per the contract. */
    public Optional<QName> responseElement(String operationId) {
        return Optional.ofNullable(responseElements.get(operationId));
    }

    /**
     * The complete schema for that operation's response element, as text — every declaration from
     * every file sharing its target namespace, merged into one document. See {@link #documentText}.
     */
    public Optional<String> documentFor(String operationId) {
        return responseElement(operationId)
                .map(QName::getNamespaceURI)
                .map(documentText::get);
    }

    /**
     * How much of what the schema declares the payload actually fills in.
     *
     * <p>Counts declared element particles present anywhere in the tree, the same rule the JSON side
     * applies, so {@code incomplete} means the same thing whichever protocol a mock belongs to.
     * Repeated elements count once — a list of ten populated items is no more complete than a list
     * of one, and weighting by length would make the number a function of how much sample data
     * someone pasted.
     *
     * @return null when nothing is declared to be complete against
     */
    public Integer completeness(String operationId, Element payload) {
        QName element = responseElements.get(operationId);
        if (element == null || payload == null) {
            return null;
        }

        Element declaration = globalElement(element);
        if (declaration == null) {
            return null;
        }

        Tally tally = new Tally();
        count(typeOf(declaration, element.getNamespaceURI()), element.getNamespaceURI(), payload, tally, 0);

        return tally.declared == 0 ? null : Math.round(100f * tally.present / tally.declared);
    }

    /**
     * An empty payload shaped like the operation's declared response — every element the schema
     * names, nested as it declares them, with nothing filled in.
     *
     * <p>Offered as a starting point, never as a mock: a skeleton served as-is would answer a
     * well-formed envelope with no data in it, which is the upstream behaviour this sandbox exists
     * to eliminate. It saves the typing, not the thinking.
     *
     * @return empty when nothing is declared to build from — an RPC-style binding, or an
     *     operation whose response element the schema does not define
     */
    public Optional<String> skeleton(String operationId) {
        return skeletonFor(responseElements.get(operationId));
    }

    /**
     * The same skeleton for any element the schema declares, not only a response.
     *
     * <p>Exists because the playground needs the other direction: a request envelope is the one
     * thing nobody wants to hand-write, and the element it must contain is already known — it is
     * what the router matches a body against. Building it from the same walk means a drafted request
     * and a drafted response cannot disagree about how the contract nests things.
     */
    public Optional<String> skeletonFor(QName element) {
        if (element == null) {
            return Optional.empty();
        }

        Element declaration = globalElement(element);
        if (declaration == null) {
            return Optional.empty();
        }

        // elementFormDefault decides whether children carry the target namespace or none. Read
        // rather than assumed: a skeleton in the wrong namespace validates as cleanly as a wrong
        // payload, which is to say not at all, and the author would be debugging our guess.
        boolean qualified = "qualified".equals(elementFormDefaultOf(element.getNamespaceURI()));

        StringBuilder out = new StringBuilder();
        out.append('<').append(element.getLocalPart());
        out.append(" xmlns=\"").append(element.getNamespaceURI()).append('"');
        out.append(">\n");

        write(typeOf(declaration, element.getNamespaceURI()), element.getNamespaceURI(), out, 1, qualified);

        out.append("</").append(element.getLocalPart()).append('>');
        return Optional.of(out.toString());
    }

    private void write(Element type, String namespace, StringBuilder out, int depth, boolean qualified) {
        // The same bound completeness uses: a self-referencing type would otherwise never finish.
        if (type == null || depth > 10) {
            return;
        }

        for (Element particle : particles(type)) {
            String name = particle.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }

            String indent = "  ".repeat(depth);
            Element childType = typeOf(particle, namespace);
            // A child in an unqualified schema carries no namespace, and would otherwise inherit
            // the default declared on the root.
            String reset = !qualified && depth == 1 ? " xmlns=\"\"" : "";

            if (childType == null) {
                out.append(indent).append('<').append(name).append(reset).append("></").append(name).append(">\n");
            } else {
                out.append(indent).append('<').append(name).append(reset).append(">\n");
                write(childType, namespace, out, depth + 1, qualified);
                out.append(indent).append("</").append(name).append(">\n");
            }
        }
    }

    /**
     * The first document of this namespace that actually declares the attribute — not simply the
     * first document of this namespace.
     *
     * <p>A namespace is routinely split across files, and the piece the WSDL carries inline is
     * often nothing but an {@code <xsd:include>} of the file that holds the real declarations. It
     * matches the namespace and declares nothing, so taking it would read "unqualified" for a
     * schema whose own file says qualified — and every generated skeleton would carry
     * {@code xmlns=""} on children that belong in the namespace.
     *
     * <p>Absent everywhere means unqualified, which is what XSD itself defaults to.
     */
    private String elementFormDefaultOf(String namespace) {
        for (Document document : documents) {
            Element schema = document.getDocumentElement();
            if (schema != null
                    && namespace.equals(schema.getAttribute("targetNamespace"))
                    && !schema.getAttribute("elementFormDefault").isBlank()) {
                return schema.getAttribute("elementFormDefault");
            }
        }
        return "";
    }

    // --- walking the schema ------------------------------------------------

    private void count(Element type, String namespace, Element instance, Tally tally, int depth) {
        // A type that refers to itself would otherwise walk forever. Ten levels is far past any
        // response a person reads on screen, and the alternative — tracking visited types — would
        // undercount a type legitimately used twice.
        if (type == null || instance == null || depth > 10) {
            return;
        }

        for (Element particle : particles(type)) {
            String name = particle.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }

            tally.declared++;

            Element child = firstChild(instance, name);
            if (child == null) {
                continue;
            }

            tally.present++;
            count(typeOf(particle, namespace), namespace, child, tally, depth + 1);
        }
    }

    /** Element declarations inside a complexType's sequence, all or choice, at any nesting. */
    private List<Element> particles(Element type) {
        List<Element> found = new ArrayList<>();
        collectParticles(type, found);
        return found;
    }

    private void collectParticles(Node parent, List<Element> found) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child) || !XSD_NS.equals(child.getNamespaceURI())) {
                continue;
            }

            switch (child.getLocalName()) {
                case "element" -> found.add(child);
                // Compositors are structure, not fields — descend without counting them.
                case "sequence", "all", "choice", "complexContent", "extension" -> collectParticles(child, found);
                default -> {}
            }
        }
    }

    /** An element declaration's type: inline, or the global complexType its {@code type} names. */
    private Element typeOf(Element declaration, String namespace) {
        for (Element child : Dom.elementChildren(declaration)) {
            if (XSD_NS.equals(child.getNamespaceURI()) && "complexType".equals(child.getLocalName())) {
                return child;
            }
        }

        String named = declaration.getAttribute("type");
        if (named.isBlank()) {
            return null;
        }

        String local = named.contains(":") ? named.substring(named.indexOf(':') + 1) : named;
        return globalDefinition("complexType", local, namespace);
    }

    private Element globalElement(QName element) {
        return globalDefinition("element", element.getLocalPart(), element.getNamespaceURI());
    }

    private Element globalDefinition(String kind, String name, String namespace) {
        for (Document document : documents) {
            Element schema = document.getDocumentElement();
            if (schema == null || !namespace.equals(schema.getAttribute("targetNamespace"))) {
                continue;
            }

            for (Element child : Dom.elementChildren(schema)) {
                if (XSD_NS.equals(child.getNamespaceURI())
                        && kind.equals(child.getLocalName())
                        && name.equals(child.getAttribute("name"))) {
                    return child;
                }
            }
        }
        return null;
    }

    private Element firstChild(Element parent, String localName) {
        for (Element child : Dom.elementChildren(parent)) {
            if (localName.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }


    private static final class Tally {
        private int declared;
        private int present;
    }
}
