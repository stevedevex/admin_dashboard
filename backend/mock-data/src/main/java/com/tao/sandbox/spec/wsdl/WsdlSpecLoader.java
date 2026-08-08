package com.tao.sandbox.spec.wsdl;

import com.tao.sandbox.config.SandboxProperties.OperationConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceConfig;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.runtime.soap.Xml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads a WSDL with DOM rather than a WSDL library.
 *
 * <p>Only four things are needed — the operations, the element that identifies each one on the
 * wire, the published endpoint address, and the target namespace. A WSDL library would supply all
 * of that plus a large dependency and a binding model this service has no use for.
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

        Map<QName, String> elementToOperation = mapBodyElementsToOperations(definitions, wsdl);
        Map<String, String> soapActions = mapSoapActions(definitions);
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
                findAddress(definitions),
                elementToOperation,
                served,
                service.namespaces() == null ? Map.of() : service.namespaces(),
                readImports(service, definitions, problems),
                readResponseHeader(service, problems));
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
     * Builds body-element → operation.
     *
     * <p>Walks portType operations to their input message, then the message part to its element.
     * For RPC-style bindings there is no element, so the operation's own name in the target
     * namespace is registered as a fallback.
     */
    private Map<QName, String> mapBodyElementsToOperations(Element definitions, Document wsdl) {
        Map<String, QName> messageElements = new LinkedHashMap<>();

        for (Element message : children(definitions, WSDL_NS, "message")) {
            String messageName = message.getAttribute("name");
            for (Element part : children(message, WSDL_NS, "part")) {
                String element = part.getAttribute("element");
                if (!element.isBlank()) {
                    messageElements.put(messageName, resolveQName(element, part));
                }
            }
        }

        Map<QName, String> byElement = new LinkedHashMap<>();
        String targetNamespace = definitions.getAttribute("targetNamespace");

        for (Element portType : children(definitions, WSDL_NS, "portType")) {
            for (Element operation : children(portType, WSDL_NS, "operation")) {
                String operationName = operation.getAttribute("name");

                QName inputElement = null;
                for (Element input : children(operation, WSDL_NS, "input")) {
                    String message = localPart(input.getAttribute("message"));
                    inputElement = messageElements.get(message);
                }

                byElement.put(
                        inputElement != null ? inputElement : new QName(targetNamespace, operationName),
                        operationName);
            }
        }

        return byElement;
    }

    private Map<String, String> mapSoapActions(Element definitions) {
        Map<String, String> actions = new LinkedHashMap<>();
        for (Element binding : children(definitions, WSDL_NS, "binding")) {
            for (Element operation : children(binding, WSDL_NS, "operation")) {
                String name = operation.getAttribute("name");
                String action = "";
                for (Element soapOperation : children(operation, SOAP_NS, "operation")) {
                    action = soapOperation.getAttribute("soapAction");
                }
                actions.put(name, action);
            }
        }
        return actions;
    }

    /**
     * Loads documents referenced by {@code schemaLocation} or {@code location}, keyed by the
     * reference as written.
     *
     * <p>Relative references are resolved against the WSDL itself, which is how a client would
     * resolve them. Only relative ones are followed: an absolute URL points somewhere the sandbox
     * does not control and should not be fetched at startup.
     */
    private Map<String, String> readImports(
            ServiceConfig service, Element definitions, List<String> problems) {

        Map<String, String> loaded = new LinkedHashMap<>();

        for (String reference : collectImportLocations(definitions)) {
            if (reference.isBlank() || reference.contains("://")) {
                continue;
            }

            try {
                Resource resource = resources.getResource(service.wsdl()).createRelative(reference);
                if (!resource.exists()) {
                    problems.add(
                            "%s: WSDL imports '%s' but it was not found next to %s"
                                    .formatted(service.id(), reference, service.wsdl()));
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    loaded.put(reference, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                problems.add(
                        "%s: could not read import '%s' — %s".formatted(service.id(), reference, e.getMessage()));
            }
        }

        return loaded;
    }

    private List<String> collectImportLocations(Element definitions) {
        List<String> locations = new ArrayList<>();
        NodeList all = definitions.getOwnerDocument().getElementsByTagName("*");

        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element element && "import".equals(element.getLocalName())) {
                String schemaLocation = element.getAttribute("schemaLocation");
                String location = element.getAttribute("location");
                if (!schemaLocation.isBlank()) {
                    locations.add(schemaLocation);
                }
                if (!location.isBlank()) {
                    locations.add(location);
                }
            }
        }

        return locations;
    }

    private String findAddress(Element definitions) {
        for (Element service : children(definitions, WSDL_NS, "service")) {
            for (Element port : children(service, WSDL_NS, "port")) {
                for (Element address : children(port, SOAP_NS, "address")) {
                    return address.getAttribute("location");
                }
            }
        }
        return null;
    }

    private QName resolveQName(String prefixed, Element scope) {
        int colon = prefixed.indexOf(':');
        if (colon < 0) {
            return new QName(prefixed);
        }
        String prefix = prefixed.substring(0, colon);
        String local = prefixed.substring(colon + 1);
        String namespace = scope.lookupNamespaceURI(prefix);
        return new QName(namespace == null ? "" : namespace, local);
    }

    private String localPart(String prefixed) {
        int colon = prefixed.indexOf(':');
        return colon < 0 ? prefixed : prefixed.substring(colon + 1);
    }

    private List<Element> children(Node parent, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element
                    && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                found.add(element);
            }
        }
        return found;
    }
}
