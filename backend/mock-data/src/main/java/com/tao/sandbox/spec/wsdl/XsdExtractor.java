package com.tao.sandbox.spec.wsdl;

import com.tao.sandbox.xml.Dom;
import com.tao.sandbox.xml.Xml;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.ls.LSResourceResolver;

/**
 * Pulls the XSD out of a WSDL, so mock payloads can be checked against the contract that describes
 * them.
 *
 * <p>Everything needed is already in the document: the schema is inline under {@code <wsdl:types>}
 * or in an imported file the loader has already read, and the response element is reachable by
 * walking {@code portType → output → message → part}. Nothing is fetched and nothing is generated
 * at build time — a WSDL dropped into a mounted directory has to work without a rebuild, which is
 * the whole reason services are configuration rather than code.
 *
 * <p>Document/literal only. An RPC-style binding has no single response element — its parts are
 * types, assembled into an operation-named wrapper by the binding — so there is nothing to validate
 * a stored payload against as a whole. Those report unavailable with the reason rather than being
 * checked against something that only resembles them.
 */
final class XsdExtractor {

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";

    private XsdExtractor() {}

    static SoapSchemas extract(
            String serviceId,
            List<Element> allDefinitions,
            Map<String, String> referenced,
            List<String> problems) {

        List<Document> inline = new ArrayList<>();
        for (Element definitions : allDefinitions) {
            for (Element schema : inlineSchemas(definitions)) {
                String text = serialise(schema);
                if (text != null) {
                    inline.add(Xml.parse(text));
                }
            }
        }

        // Every reference the WSDL reaches, transitively — schema and non-schema alike, since a
        // multi-file contract is not always split by namespace. Only the ones that are actually XSD
        // matter here; a referenced WSDL (rare, but legal) is not a schema fragment.
        List<Document> allDocuments = new ArrayList<>(inline);
        Map<String, String> referencedSchemas = new LinkedHashMap<>();

        referenced.forEach(
                (reference, content) -> {
                    try {
                        Document document = Xml.parse(content);
                        if (XSD_NS.equals(document.getDocumentElement().getNamespaceURI())) {
                            referencedSchemas.put(reference, content);
                            allDocuments.add(document);
                        }
                    } catch (RuntimeException e) {
                        problems.add(
                                "%s: a referenced schema could not be parsed — %s".formatted(serviceId, e.getMessage()));
                    }
                });

        if (allDocuments.isEmpty()) {
            return SoapSchemas.none("The WSDL declares no schema, inline or referenced");
        }

        Map<String, QName> responses = responseElements(allDefinitions);
        if (responses.isEmpty()) {
            return SoapSchemas.none(
                    "No operation declares a response element — an RPC-style binding has none to declare");
        }

        Map<String, String> merged = mergeByNamespace(allDocuments);

        Schema compiled;
        try {
            compiled = compile(inline, referencedSchemas);
        } catch (Exception e) {
            // Startup is not failed over this. The service still serves mocks perfectly well; only
            // validation is lost, and losing it loudly beats refusing to start over a schema that
            // request handling never reads.
            problems.add(
                    "%s: schema present but not compilable, so payloads cannot be validated — %s"
                            .formatted(serviceId, e.getMessage()));
            return new SoapSchemas(
                    null, responses, allDocuments, merged, "The declared schema could not be compiled: " + e.getMessage());
        }

        return new SoapSchemas(compiled, responses, allDocuments, merged, null);
    }

    /**
     * One coherent schema per target namespace, for display.
     *
     * <p>A namespace is routinely split across several files — a WSDL's own inline fragment plus
     * whatever it {@code <xsd:include>}s — and showing only the first one found, as an earlier
     * version of this did, left the actual field definitions invisible to whoever opened the schema
     * view: {@code userservice}'s WSDL carries almost nothing beyond the include line itself. Every
     * top-level declaration contributed by any document sharing a namespace is merged into one
     * well-formed {@code <xsd:schema>}. The {@code <xsd:import>}/{@code <xsd:include>}/{@code
     * <xsd:redefine>} elements that pulled the pieces together are dropped from the result — kept,
     * they would dangle, pointing at a file whose content is already inlined right below them.
     *
     * <p>Only merges within one namespace. A type reached through {@code <xsd:import>} — a
     * different namespace by definition — is compiled and validated correctly regardless, but is not
     * pulled into this merged view; see the note on {@link SoapSchemas#completeness} for why that
     * boundary is left for later.
     */
    private static Map<String, String> mergeByNamespace(List<Document> documents) {
        Map<String, List<Element>> byNamespace = new LinkedHashMap<>();
        for (Document document : documents) {
            Element schema = document.getDocumentElement();
            if (schema != null) {
                byNamespace.computeIfAbsent(schema.getAttribute("targetNamespace"), ns -> new ArrayList<>()).add(schema);
            }
        }

        Map<String, String> merged = new LinkedHashMap<>();
        byNamespace.forEach((namespace, schemas) -> {
            String text = schemas.size() == 1 ? serialise(schemas.get(0)) : serialise(merge(schemas));
            if (text != null) {
                merged.put(namespace, text);
            }
        });
        return merged;
    }

    /** Combines every top-level declaration from {@code schemas} — all one namespace — into one element. */
    private static Element merge(List<Element> schemas) {
        Document target;
        try {
            target = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (Exception e) {
            // DocumentBuilderFactory with no special configuration does not fail in practice.
            throw new IllegalStateException(e);
        }

        Element root = target.createElementNS(XSD_NS, "xsd:schema");
        target.appendChild(root);

        Set<String> declaredPrefixes = new LinkedHashSet<>();
        for (Element schema : schemas) {
            copyNamespaceDeclarations(schema, root, declaredPrefixes);
            for (String attribute : List.of("targetNamespace", "elementFormDefault", "attributeFormDefault")) {
                if (!root.hasAttribute(attribute) && schema.hasAttribute(attribute)) {
                    root.setAttribute(attribute, schema.getAttribute(attribute));
                }
            }
        }

        for (Element schema : schemas) {
            for (Element child : Dom.elementChildren(schema)) {
                String name = child.getLocalName();
                if (!"import".equals(name) && !"include".equals(name) && !"redefine".equals(name)) {
                    root.appendChild(target.importNode(child, true));
                }
            }
        }

        return root;
    }

    private static void copyNamespaceDeclarations(Element source, Element target, Set<String> seen) {
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            if (attributes.item(i) instanceof Attr attribute
                    && XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    && seen.add(attribute.getName())) {
                target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getName(), attribute.getValue());
            }
        }
    }


    /**
     * Compiles the inline schema(s) as the roots, resolving every {@code <xsd:import>} and
     * {@code <xsd:include>} they reach against {@code referenced} — content already fetched by
     * {@link WsdlSpecLoader}, never against the filesystem or network at compile time.
     *
     * <p>A plain root-{@code Source} list per referenced file was tried first and does not work:
     * {@link SchemaFactory} resolves {@code schemaLocation} references inside a schema document
     * itself, on its own, the moment it parses one — supplying the target as an independent extra
     * root does not stop it from also trying to fetch the reference directly, which is exactly what
     * {@code ACCESS_EXTERNAL_SCHEMA} exists to block. An {@link LSResourceResolver} is the only hook
     * JAXP offers for handing over content instead of a location to fetch it from.
     */
    private static Schema compile(List<Document> inline, Map<String, String> referenced) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver(inMemoryResolver(referenced));

        DOMSource[] sources = inline.stream().map(DOMSource::new).toArray(DOMSource[]::new);
        return factory.newSchema(sources);
    }

    /**
     * Answers every {@code schemaLocation} lookup from the in-memory map rather than a real
     * location. Matched on the reference exactly as it was written in the schema, ignoring the
     * resolver's {@code baseURI} — {@link WsdlSpecLoader} fetches every reference relative to the
     * WSDL itself regardless of which file did the including, so the same flat keying is correct
     * here too. See {@link WsdlSpecLoader#collectAllSchemaReferences} for what that assumes.
     */
    private static LSResourceResolver inMemoryResolver(Map<String, String> referenced) {
        return (type, namespaceURI, publicId, systemId, baseURI) -> {
            String content = systemId == null ? null : referenced.get(systemId);
            return content == null ? null : new InMemoryLSInput(systemId, content);
        };
    }

    /**
     * operation name → the element its response payload must be. Messages and portTypes may live
     * in different documents of a split contract, so both walks span all of them.
     */
    private static Map<String, QName> responseElements(List<Element> allDefinitions) {
        Map<String, QName> messageElements = new LinkedHashMap<>();

        for (Element definitions : allDefinitions) {
            for (Element message : Dom.children(definitions, WSDL_NS, "message")) {
                for (Element part : Dom.children(message, WSDL_NS, "part")) {
                    String element = part.getAttribute("element");
                    if (!element.isBlank()) {
                        messageElements.put(message.getAttribute("name"), Dom.qnameOf(element, part));
                    }
                }
            }
        }

        Map<String, QName> byOperation = new LinkedHashMap<>();

        for (Element definitions : allDefinitions) {
            for (Element portType : Dom.children(definitions, WSDL_NS, "portType")) {
                for (Element operation : Dom.children(portType, WSDL_NS, "operation")) {
                    for (Element output : Dom.children(operation, WSDL_NS, "output")) {
                        QName element = messageElements.get(Dom.localPart(output.getAttribute("message")));
                        if (element != null) {
                            byOperation.put(operation.getAttribute("name"), element);
                        }
                    }
                }
            }
        }

        return byOperation;
    }

    private static List<Element> inlineSchemas(Element definitions) {
        List<Element> schemas = new ArrayList<>();
        for (Element types : Dom.children(definitions, WSDL_NS, "types")) {
            schemas.addAll(Dom.children(types, XSD_NS, "schema"));
        }
        return schemas;
    }

    /**
     * Serialises an inline schema as a standalone document.
     *
     * <p>Namespace declarations are copied down from the ancestors first. An inline schema routinely
     * uses a prefix bound on {@code <wsdl:definitions>} — {@code type="xsd1:TradePrice"} where
     * {@code xsd1} is declared on the root — and lifted out without it the prefix is unbound and the
     * schema will not compile.
     */
    private static String serialise(Element schema) {
        Element standalone = (Element) schema.cloneNode(true);
        copyAncestorNamespaces(schema, standalone);

        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(standalone), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyAncestorNamespaces(Element original, Element target) {
        for (Node ancestor = original.getParentNode();
                ancestor instanceof Element element;
                ancestor = ancestor.getParentNode()) {

            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                if (attributes.item(i) instanceof Attr attribute
                        && XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                        // A binding on the schema itself, or on a nearer ancestor, already won.
                        && !target.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getLocalName())) {
                    target.setAttributeNS(
                            XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getName(), attribute.getValue());
                }
            }
        }
    }



}
