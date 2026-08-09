package com.tao.sandbox.spec.wsdl;

import com.tao.sandbox.config.SandboxProperties.OperationConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceConfig;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.xml.Dom;
import com.tao.sandbox.xml.Xml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads a WSDL with DOM rather than a WSDL library.
 *
 * <p>Only a handful of things are needed — the operations, the element that identifies each one on
 * the wire, the published endpoint address, the target namespace, and the schema describing what
 * each operation returns. A WSDL library would supply all of that plus a large dependency and a
 * binding model this service has no use for.
 */
@Component
public class WsdlSpecLoader {

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";

    private final DefaultResourceLoader resources = new DefaultResourceLoader();

    public SoapServiceDefinition load(ServiceConfig service, List<String> problems) {
        String raw = read(service, problems);
        if (raw == null) {
            return null;
        }

        Document wsdl;
        try {
            wsdl = Xml.parse(raw);
        } catch (RuntimeException e) {
            problems.add("%s: could not parse %s — %s".formatted(service.id(), service.wsdl(), e.getMessage()));
            return null;
        }

        Element definitions = wsdl.getDocumentElement();
        String targetNamespace = definitions.getAttribute("targetNamespace");

        Map<String, String> imports = collectAllSchemaReferences(service, definitions, problems);

        // A contract split across WSDL documents — interface in one, binding and address in
        // another, joined by <wsdl:import> — is a standard shape from contract-first .NET and
        // Java stacks. Reading only the top-level document would find the binding and no
        // operations, so every WSDL document reached by import contributes on equal terms.
        List<Element> allDefinitions = withImportedWsdls(definitions, imports);

        Map<QName, String> elementToOperation = mapBodyElementsToOperations(allDefinitions);
        Map<String, String> soapActions = mapSoapActions(allDefinitions);
        List<String> declared = new ArrayList<>(soapActions.keySet());
        if (declared.isEmpty()) {
            declared = new ArrayList<>(elementToOperation.values());
        }

        Map<String, SoapOperationDefinition> served = new LinkedHashMap<>();
        for (OperationConfig configured : service.operations()) {
            String name = configured.operation();

            if (name == null || name.isBlank()) {
                problems.add("%s: SOAP operations must declare an 'operation'".formatted(service.id()));
                continue;
            }
            if (!declared.contains(name)) {
                problems.add(
                        "%s: operation '%s' is not in %s. Available: %s"
                                .formatted(service.id(), name, service.wsdl(), declared.stream().sorted().toList()));
                continue;
            }

            List<KeySpec> keys = new ArrayList<>();
            for (String declaration : configured.keys()) {
                try {
                    keys.add(KeySpec.parse(declaration));
                } catch (IllegalArgumentException e) {
                    problems.add("%s/%s: %s".formatted(service.id(), name, e.getMessage()));
                }
            }

            served.put(
                    name,
                    new SoapOperationDefinition(
                            service.id(), name, soapActions.get(name), keys, configured.strategy()));
        }

        if (service.path() == null || service.path().isBlank()) {
            problems.add("%s: SOAP services must declare a 'path'".formatted(service.id()));
            return null;
        }

        return new SoapServiceDefinition(
                service.id(),
                service.path(),
                raw,
                targetNamespace,
                findAddress(allDefinitions),
                elementToOperation,
                served,
                service.namespaces() == null ? Map.of() : service.namespaces(),
                imports,
                readResponseHeader(service, problems),
                XsdExtractor.extract(service.id(), allDefinitions, imports, problems));
    }

    /**
     * The main document first, then every referenced document whose root is
     * {@code wsdl:definitions}. Parse failures are not re-reported — the reference collector
     * already named them.
     */
    private List<Element> withImportedWsdls(Element definitions, Map<String, String> imports) {
        List<Element> all = new ArrayList<>();
        all.add(definitions);

        for (String content : imports.values()) {
            try {
                Element root = Xml.parse(content).getDocumentElement();
                if (WSDL_NS.equals(root.getNamespaceURI()) && "definitions".equals(root.getLocalName())) {
                    all.add(root);
                }
            } catch (RuntimeException e) {
                // Already reported by collectAllSchemaReferences.
            }
        }

        return all;
    }

    /** The service-wide envelope header, if one is configured. */
    private String readResponseHeader(ServiceConfig service, List<String> problems) {
        if (service.responseHeader() == null || service.responseHeader().isBlank()) {
            return null;
        }

        Resource resource = resources.getResource(service.responseHeader());
        if (!resource.exists()) {
            problems.add(
                    "%s: responseHeader not found at %s".formatted(service.id(), service.responseHeader()));
            return null;
        }

        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            problems.add("%s: could not read responseHeader — %s".formatted(service.id(), e.getMessage()));
            return null;
        }
    }

    private String read(ServiceConfig service, List<String> problems) {
        if (service.wsdl() == null || service.wsdl().isBlank()) {
            problems.add("%s: SOAP services must declare a 'wsdl'".formatted(service.id()));
            return null;
        }

        Resource resource = resources.getResource(service.wsdl());
        if (!resource.exists()) {
            problems.add("%s: WSDL not found at %s".formatted(service.id(), service.wsdl()));
            return null;
        }

        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add("%s: could not read %s — %s".formatted(service.id(), service.wsdl(), e.getMessage()));
            return null;
        }
    }

    /**
     * Builds body-element → operation, across every WSDL document of the contract.
     *
     * <p>Messages are indexed first from all documents, then portTypes are walked — the two
     * routinely live in different documents of a split contract. For RPC-style bindings there is
     * no input element, so the operation's own name in its document's target namespace is
     * registered as a fallback.
     */
    private Map<QName, String> mapBodyElementsToOperations(List<Element> allDefinitions) {
        Map<String, QName> messageElements = new LinkedHashMap<>();

        for (Element definitions : allDefinitions) {
            for (Element message : Dom.children(definitions, WSDL_NS, "message")) {
                String messageName = message.getAttribute("name");
                for (Element part : Dom.children(message, WSDL_NS, "part")) {
                    String element = part.getAttribute("element");
                    if (!element.isBlank()) {
                        messageElements.put(messageName, Dom.qnameOf(element, part));
                    }
                }
            }
        }

        Map<QName, String> byElement = new LinkedHashMap<>();

        for (Element definitions : allDefinitions) {
            String targetNamespace = definitions.getAttribute("targetNamespace");

            for (Element portType : Dom.children(definitions, WSDL_NS, "portType")) {
                for (Element operation : Dom.children(portType, WSDL_NS, "operation")) {
                    String operationName = operation.getAttribute("name");

                    QName inputElement = null;
                    for (Element input : Dom.children(operation, WSDL_NS, "input")) {
                        String message = Dom.localPart(input.getAttribute("message"));
                        inputElement = messageElements.get(message);
                    }

                    byElement.put(
                            inputElement != null ? inputElement : new QName(targetNamespace, operationName),
                            operationName);
                }
            }
        }

        return byElement;
    }

    private Map<String, String> mapSoapActions(List<Element> allDefinitions) {
        Map<String, String> actions = new LinkedHashMap<>();
        for (Element definitions : allDefinitions) {
            for (Element binding : Dom.children(definitions, WSDL_NS, "binding")) {
                for (Element operation : Dom.children(binding, WSDL_NS, "operation")) {
                    String name = operation.getAttribute("name");
                    String action = "";
                    for (Element soapOperation : Dom.children(operation, SOAP_NS, "operation")) {
                        action = soapOperation.getAttribute("soapAction");
                    }
                    actions.put(name, action);
                }
            }
        }
        return actions;
    }

    /**
     * Every document reachable from the WSDL by {@code <xsd:import>}, {@code <xsd:include>} or
     * {@code <wsdl:import>}, keyed by the reference exactly as written — transitively, since a
     * schema split across files routinely has one file including another.
     *
     * <p>{@code <xsd:include>} matters as much as {@code <xsd:import>} here even though the two mean
     * different things — import brings in a different namespace, include extends the same one — a
     * type or element declared only in an included file is invisible to validation otherwise, and a
     * multi-file contract split by convention rather than namespace is the common shape, not the
     * exception.
     *
     * <p>Every reference is resolved relative to the WSDL's own location, not to whichever document
     * did the including. That matches every real example seen so far — an unpacked contract with the
     * WSDL and every {@code .xsd} it needs sitting flat as siblings — and is simpler and more
     * predictable than tracking a different base per file for a directory layout nothing has needed
     * yet. A reference that needs resolving relative to its own including document, rather than the
     * WSDL, is not supported; such a WSDL would need this revisited, not silently mishandled.
     *
     * <p>Only relative references are followed: an absolute URL points somewhere the sandbox does
     * not control and should not be fetched at startup.
     */
    private Map<String, String> collectAllSchemaReferences(
            ServiceConfig service, Element definitions, List<String> problems) {

        Map<String, String> loaded = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(referencesIn(definitions));
        Set<String> queued = new HashSet<>(queue);

        while (!queue.isEmpty()) {
            String reference = queue.poll();

            if (reference.isBlank() || reference.contains("://") || loaded.containsKey(reference)) {
                continue;
            }

            Resource resource;
            try {
                resource = resources.getResource(service.wsdl()).createRelative(reference);
                if (!resource.exists()) {
                    problems.add(
                            "%s: WSDL references '%s' but it was not found next to %s"
                                    .formatted(service.id(), reference, service.wsdl()));
                    continue;
                }
            } catch (IOException e) {
                problems.add(
                        "%s: could not resolve reference '%s' — %s"
                                .formatted(service.id(), reference, e.getMessage()));
                continue;
            }

            String content;
            try (InputStream in = resource.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                problems.add(
                        "%s: could not read reference '%s' — %s"
                                .formatted(service.id(), reference, e.getMessage()));
                continue;
            }

            loaded.put(reference, content);

            // A schema or WSDL fetched here may itself import or include further siblings — the
            // second and third screenshots this design was checked against both have exactly this
            // shape, files referenced only from within another referenced file, never from the WSDL
            // directly.
            try {
                Element root = Xml.parse(content).getDocumentElement();
                for (String nested : referencesIn(root)) {
                    if (queued.add(nested)) {
                        queue.add(nested);
                    }
                }
            } catch (RuntimeException e) {
                problems.add(
                        "%s: '%s' could not be parsed, so anything it references is unreachable — %s"
                                .formatted(service.id(), reference, e.getMessage()));
            }
        }

        return loaded;
    }

    /** {@code schemaLocation} or {@code location} on any {@code import}, {@code include} or {@code redefine}. */
    private List<String> referencesIn(Element root) {
        List<String> locations = new ArrayList<>();
        NodeList all = root.getOwnerDocument().getElementsByTagName("*");

        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element element)) {
                continue;
            }
            String name = element.getLocalName();
            if (!"import".equals(name) && !"include".equals(name) && !"redefine".equals(name)) {
                continue;
            }

            String schemaLocation = element.getAttribute("schemaLocation");
            String location = element.getAttribute("location");
            if (!schemaLocation.isBlank()) {
                locations.add(schemaLocation);
            }
            if (!location.isBlank()) {
                locations.add(location);
            }
        }

        return locations;
    }

    private String findAddress(List<Element> allDefinitions) {
        for (Element definitions : allDefinitions) {
            for (Element service : Dom.children(definitions, WSDL_NS, "service")) {
                for (Element port : Dom.children(service, WSDL_NS, "port")) {
                    for (Element address : Dom.children(port, SOAP_NS, "address")) {
                        return address.getAttribute("location");
                    }
                }
            }
        }
        return null;
    }

}
