package com.tao.sandbox.control;

import com.tao.sandbox.control.view.PlaygroundDraft;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.runtime.soap.SoapEnvelope;
import com.tao.sandbox.runtime.soap.SoapVersion;
import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.spec.wsdl.SoapOperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import com.tao.sandbox.xml.Xml;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.util.Iterator;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A request worth sending, composed from the contract.
 *
 * <p>The playground is only as useful as its first click, and an empty text box makes that click a
 * fault. A drafted request instead arrives already addressed to the operation and already carrying
 * the values that identify it, so the first thing somebody sees is a mock answering — and the
 * failures they go on to investigate are their own, not the blank page's.
 *
 * <h2>Why the values land where they do</h2>
 *
 * <p>Nothing here guesses where a key belongs. Every key already declares where it is read from —
 * {@code path:petId}, {@code query:limit}, {@code body:$.name}, an XPath into the envelope — so the
 * draft writes each value at exactly the location its own declaration names. That is what makes a
 * drafted request resolve to the mock it was drafted from, rather than merely look plausible: the
 * value is placed where extraction will go looking for it, by definition.
 */
@Component
class PlaygroundDrafts {

    /**
     * Jackson 2, like {@link Skeletons} and the validator: the schema text these read came from the
     * swagger-parser model, which is a Jackson 2 model. The runtime's own request handling is on
     * Jackson 3, and the two must not be mixed inside one document.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SpecRegistry registry;

    PlaygroundDrafts(SpecRegistry registry) {
        this.registry = registry;
    }

    PlaygroundDraft draft(String serviceId, String operationId, Map<String, String> keys) {
        if (serviceId == null || serviceId.isBlank() || operationId == null || operationId.isBlank()) {
            throw ControlPanelProblem.badRequest(
                    "missing-operation", "Nothing to draft", "Name a service and an operation to draft a request for");
        }

        Map<String, String> supplied = keys == null ? Map.of() : keys;

        Optional<OperationDefinition> rest = registry.findRest(serviceId, operationId);
        if (rest.isPresent()) {
            return restDraft(rest.get(), supplied);
        }

        return soapDraft(serviceId, operationId, supplied);
    }

    // --- REST --------------------------------------------------------------

    /**
     * Method and path come from the contract; only the identifying values are ours to fill.
     *
     * <p>A path variable left unfilled stays as its own template — {@code /pets/{petId}} — rather
     * than becoming a placeholder somebody has to recognise as fake. The template is what the
     * contract says, and a reader who has to supply a value is better served seeing which one.
     */
    private PlaygroundDraft restDraft(OperationDefinition operation, Map<String, String> keys) {
        String path = operation.path();
        Map<String, String> query = new LinkedHashMap<>();
        List<String> unfilled = new ArrayList<>();

        // Shaped by the contract first, then identified by the keys — the same order the SOAP side
        // works in, and for the same reason. A body assembled only from the keys satisfies
        // resolution while omitting whatever else the schema requires, so it reaches the right mock
        // and is not a request the real service would have accepted. Anyone using the playground to
        // check what their client should send would be reading a shape that does not exist.
        ObjectNode body = skeletonFor(operation);

        for (KeySpec key : operation.keys()) {
            String value = valueFor(key, keys);

            if (value == null) {
                unfilled.add(key.name());
                continue;
            }

            switch (key.source()) {
                case PATH -> path = path.replace("{" + key.expression() + "}", value);
                case QUERY -> query.put(key.expression(), value);
                // A key may name a field the schema does not declare, or the operation may declare
                // no body schema at all. Either way the value has to go in, because without it the
                // request cannot reach the mock it was drafted from.
                case BODY -> {
                    if (body == null) {
                        body = JsonNodeFactory.instance.objectNode();
                    }
                    write(body, key.expression(), value);
                }
                // A header key is identity the sandbox reads but a drafted URL cannot show. Named in
                // the note instead of dropped silently.
                case HEADER -> unfilled.add(key.name() + " (header)");
                case XPATH -> unfilled.add(key.name() + " (xpath, on a REST operation)");
            }
        }

        String queryString =
                query.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .reduce((a, b) -> a + "&" + b)
                        .map(joined -> "?" + joined)
                        .orElse("");

        return new PlaygroundDraft(
                operation.method().name(),
                path + queryString,
                body == null ? null : body.toPrettyString(),
                body == null ? null : MediaType.APPLICATION_JSON_VALUE,
                noteFor(unfilled));
    }

    /**
     * An empty body shaped like the operation's declared request, or null when it declares none.
     *
     * <p>Built by {@link Skeletons}, which is what already shapes a mock's response payload from a
     * schema. One generator for both directions: a request skeleton produced by a second one would
     * be free to disagree with the response skeleton about how the same contract nests things.
     */
    private ObjectNode skeletonFor(OperationDefinition operation) {
        String skeleton =
                Skeletons.fromJsonSchema(
                        registry.findRequestSchema(operation.serviceId(), operation.operationId())
                                .orElse(null));

        if (skeleton == null) {
            return null;
        }

        try {
            // Only an object can carry a key at a pointer. A schema describing a bare array or a
            // scalar is legal and rare, and there is nowhere in one to write `$.name` — so it is
            // left to the key path to build what it needs.
            return JSON.readTree(skeleton) instanceof ObjectNode object ? object : null;
        } catch (Exception e) {
            // The skeleton is this codebase's own output, so this cannot normally happen — and a
            // draft is still worth having from the keys alone if it somehow does.
            return null;
        }
    }

    /**
     * Writes a value at a dotted JSONPath, creating the objects on the way.
     *
     * <p>The same {@code $.a.b} subset extraction reads, because a draft written to any other shape
     * would be a request the extractor cannot find its own key in.
     */
    private void write(ObjectNode root, String expression, String value) {
        String[] segments = expression.replaceFirst("^\\$\\.?", "").split("\\.");

        ObjectNode node = root;
        for (int i = 0; i < segments.length - 1; i++) {
            // Reuses the object the skeleton already put there, so writing a key into a nested
            // structure fills it in rather than replacing it. `putObject` rather than `withObject`:
            // the latter reads its argument as a JSON Pointer in Jackson 2, which would silently
            // create a property literally named after the segment at the root.
            JsonNode existing = node.get(segments[i]);
            node = existing instanceof ObjectNode child ? child : node.putObject(segments[i]);
        }
        node.put(segments[segments.length - 1], value);
    }

    // --- SOAP --------------------------------------------------------------

    /**
     * An envelope containing the element the router matches on, shaped by the schema.
     *
     * <p>Drafted as SOAP 1.1. Both versions resolve identically — the pipeline binds {@code soapenv}
     * to whichever arrived — so the choice only decides what a reader sees first, and 1.1 is what
     * the older clients this sandbox stands in for actually send.
     */
    private PlaygroundDraft soapDraft(String serviceId, String operationId, Map<String, String> keys) {
        SoapServiceDefinition service =
                registry.soapServices().stream()
                        .filter(candidate -> candidate.serviceId().equals(serviceId))
                        .filter(candidate -> candidate.served().containsKey(operationId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.notFound(
                                                "no-such-operation",
                                                "Nothing serves that",
                                                "%s/%s is not a served operation".formatted(serviceId, operationId)));

        SoapOperationDefinition operation = service.served().get(operationId);

        QName requestElement =
                service.elementToOperation().entrySet().stream()
                        .filter(entry -> entry.getValue().equals(operationId))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.unprocessable(
                                                "no-request-element",
                                                "Cannot draft this one",
                                                "%s's contract does not say which element a %s request carries."
                                                        .formatted(serviceId, operationId)));

        String payload =
                service.schemas()
                        .skeletonFor(requestElement)
                        .orElseGet(
                                // The element is known even when the schema will not yield a shape —
                                // an RPC binding, or a type declared somewhere the sandbox declined
                                // to follow. An empty element addressed to the right operation still
                                // reaches the pipeline, which is more use than nothing.
                                () ->
                                        "<%s xmlns=\"%s\"></%s>"
                                                .formatted(
                                                        requestElement.getLocalPart(),
                                                        requestElement.getNamespaceURI(),
                                                        requestElement.getLocalPart()));

        String envelope = SoapEnvelope.wrap(payload, SoapVersion.SOAP_1_1, null);
        List<String> unfilled = new ArrayList<>();

        return new PlaygroundDraft(
                null,
                service.path(),
                fill(envelope, operation, service, keys, unfilled),
                SoapVersion.SOAP_1_1.contentType(),
                noteFor(unfilled));
    }

    /**
     * Sets each key's value at the XPath that key is read from.
     *
     * <p>A path that matches nothing is reported rather than forced. The usual cause is a key
     * declared against an element the schema does not put in the skeleton — an optional field, or a
     * choice branch — and inventing the element would produce an envelope the contract does not
     * describe while claiming the draft succeeded.
     */
    private String fill(
            String envelope,
            SoapOperationDefinition operation,
            SoapServiceDefinition service,
            Map<String, String> keys,
            List<String> unfilled) {

        Document document = Xml.parse(envelope);
        XPath xpath = XPathFactory.newDefaultInstance().newXPath();
        xpath.setNamespaceContext(context(service.namespaces()));

        boolean changed = false;

        for (KeySpec key : operation.keys()) {
            String value = valueFor(key, keys);
            if (value == null) {
                unfilled.add(key.name());
                continue;
            }

            if (key.source() != KeySpec.Source.XPATH) {
                unfilled.add(key.name() + " (" + key.source().name().toLowerCase(java.util.Locale.ROOT) + ")");
                continue;
            }

            try {
                Node node = (Node) xpath.evaluate(key.expression(), document, XPathConstants.NODE);
                if (node == null) {
                    unfilled.add(key.name() + " (not in the drafted envelope)");
                    continue;
                }
                node.setTextContent(value);
                changed = true;
            } catch (Exception e) {
                unfilled.add(key.name() + " (" + e.getMessage() + ")");
            }
        }

        // Serialising costs the envelope its hand-written indentation, so it is only paid for when
        // something was actually written into it.
        return changed ? Xml.serialize(document) : envelope;
    }

    /** {@code soapenv} is bound here so no configuration has to declare it, as everywhere else. */
    private NamespaceContext context(Map<String, String> configured) {
        Map<String, String> bindings = new LinkedHashMap<>(configured);
        bindings.put("soapenv", SoapVersion.SOAP_1_1.envelopeNamespace());

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

    // --- internals ---------------------------------------------------------

    /**
     * Matched on the key's name, case-insensitively, because that is the spelling a file name uses
     * and a file name is where these values come from.
     */
    private String valueFor(KeySpec key, Map<String, String> keys) {
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key.name()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String noteFor(List<String> unfilled) {
        return unfilled.isEmpty()
                ? null
                : "Nothing was supplied for %s, so the draft leaves it out — the request will resolve to the operation's default until it is filled in."
                        .formatted(String.join(", ", unfilled));
    }
}
