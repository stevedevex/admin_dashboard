package com.tao.sandbox.validate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tao.sandbox.runtime.soap.Xml;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.spec.wsdl.SoapSchemas;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Checks a payload against the contract its operation declares.
 *
 * <p>Takes a body rather than a mock id: the editor validates what is on screen, not what was last
 * saved, and an author fixing a mock needs the verdict before deciding whether to keep it.
 *
 * <p>JSON is checked against the OpenAPI document's schema; XML against the XSD taken out of the
 * WSDL, plus a check that the payload is the element the operation actually returns.
 *
 * <p>Uses Jackson 2 ({@code com.fasterxml.jackson}) for JSON, because the schema validator is
 * built on it, while the rest of the application is on the Jackson 3 ({@code tools.jackson}) that
 * Spring Boot 4 ships. Mixing the two in one method is the kind of thing that compiles and then
 * fails on a {@code JsonNode} that is not the {@code JsonNode} the other library meant.
 */
@Component
public class MockValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final SpecRegistry registry;

    /**
     * Compiled once per operation, forever: the registry's schemas are fixed at startup, so there
     * is nothing to invalidate. Without this every validate call re-parsed and re-compiled the
     * schema — noticeable pain when the dashboard sweeps a whole library.
     */
    private final ConcurrentHashMap<String, CompiledSchema> compiled = new ConcurrentHashMap<>();

    /** The tree is kept alongside the validator because completeness counting walks the tree. */
    private record CompiledSchema(JsonNode node, JsonSchema schema) {}

    public MockValidator(SpecRegistry registry) {
        this.registry = registry;
    }

    /**
     * @throws IllegalArgumentException if the operation is not served, since there is then no
     *     contract to check against and reporting "valid" would be an invention
     */
    public Validation validate(String serviceId, String operationId, String body) {
        if (registry.findOperation(serviceId, operationId).isEmpty()) {
            throw new IllegalArgumentException("%s/%s is not served".formatted(serviceId, operationId));
        }

        return registry.findRest(serviceId, operationId).isPresent()
                ? validateJson(serviceId, operationId, body)
                : validateXml(serviceId, operationId, body);
    }

    private Validation validateJson(String serviceId, String operationId, String body) {
        JsonNode instance;
        try {
            instance = JSON.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            return malformed("$", e.getLocation() == null ? null : e.getLocation().getLineNr(), e.getOriginalMessage());
        }

        if (instance == null || instance.isMissingNode()) {
            return malformed("$", 1, "The payload is empty");
        }

        Optional<String> declared = registry.findResponseSchema(serviceId, operationId);
        if (declared.isEmpty()) {
            // The contract declares no response body. Parsing is genuinely all that was checked,
            // and saying so is what stops the dashboard rendering this as a clean bill of health.
            return Validation.clean(Validation.Checked.SYNTAX, null);
        }

        CompiledSchema schema = compiled.get(serviceId + "/" + operationId);
        if (schema == null) {
            try {
                JsonNode schemaNode = nullableToUnion(JSON.readTree(declared.get()));
                JsonSchema jsonSchema = SCHEMAS.getSchema(schemaNode);
                // Eager, so concurrent validate calls never race lazy validator construction.
                jsonSchema.initializeValidators();
                schema = new CompiledSchema(schemaNode, jsonSchema);
                compiled.put(serviceId + "/" + operationId, schema);
            } catch (JsonProcessingException e) {
                // The schema came out of a document this service parsed at startup, so this should
                // not happen — but reporting the payload as valid because our own schema broke
                // would be a lie in the one direction that matters.
                return new Validation(
                        false,
                        Validation.Checked.NONE,
                        null,
                        List.of(
                                new Validation.Issue(
                                        "$", null, "Unreadable schema: " + e.getOriginalMessage(), "schema")));
            }
        }

        List<Validation.Issue> issues =
                schema.schema().validate(instance).stream()
                        .sorted(Comparator.comparing(ValidationMessage::getInstanceLocation))
                        .map(
                                message ->
                                        new Validation.Issue(
                                                // Already rooted at $ — prefixing gives $$.
                                                message.getInstanceLocation().toString(),
                                                // Schema checks work on a parsed tree and have no
                                                // line to report.
                                                null,
                                                message.getMessage(),
                                                message.getType()))
                        .toList();

        return new Validation(
                issues.isEmpty(), Validation.Checked.SCHEMA, completeness(schema.node(), instance), issues);
    }

    private Validation validateXml(String serviceId, String operationId, String body) {
        Document payload;
        try {
            payload = Xml.parse(body == null ? "" : body);
        } catch (RuntimeException e) {
            Integer line =
                    e.getCause() instanceof SAXParseException parse && parse.getLineNumber() > 0
                            ? parse.getLineNumber()
                            : null;
            return malformed("/", line, e.getMessage());
        }

        Optional<SoapSchemas> schemas = registry.soapSchemas(serviceId);
        Optional<Schema> compiled = schemas.flatMap(SoapSchemas::compiled);
        Optional<QName> declared = schemas.flatMap(set -> set.responseElement(operationId));

        if (compiled.isEmpty() || declared.isEmpty()) {
            // Well-formed is all that can honestly be claimed. Reporting SCHEMA here would make a
            // payload nothing checked look exactly like one that passed.
            return Validation.clean(Validation.Checked.SYNTAX, null);
        }

        Element root = payload.getDocumentElement();
        QName actual = new QName(root.getNamespaceURI() == null ? "" : root.getNamespaceURI(), root.getLocalName());

        if (!declared.get().equals(actual)) {
            // A mock holding the *request* element would validate cleanly — both are declared in the
            // same schema — and serve something no client can read. The schema alone cannot catch
            // that, because it never learns which of its elements this file was meant to be.
            return new Validation(
                    false,
                    Validation.Checked.SCHEMA,
                    null,
                    List.of(
                            new Validation.Issue(
                                    "/" + root.getLocalName(),
                                    null,
                                    "%s returns %s, but this payload is %s"
                                            .formatted(operationId, declared.get(), actual),
                                    "element")));
        }

        List<Validation.Issue> issues = new ArrayList<>();
        try {
            Validator validator = compiled.get().newValidator();
            validator.setErrorHandler(collectInto(issues));
            // Validated from the text rather than the DOM already parsed above. A DOM carries no
            // source positions, so every issue would come back with a null line — and "somewhere in
            // this file" is a poor answer for the one format where the payload is often long.
            validator.validate(new StreamSource(new StringReader(body)));
        } catch (SAXParseException e) {
            issues.add(issue(e));
        } catch (Exception e) {
            return malformed("/", null, String.valueOf(e.getMessage()));
        }

        return new Validation(
                issues.isEmpty(),
                Validation.Checked.SCHEMA,
                schemas.get().completeness(operationId, root),
                issues);
    }

    /**
     * Collects every complaint rather than stopping at the first.
     *
     * <p>A validator left to its default throws on the first error, and an author fixing a payload
     * one message at a time re-runs it once per field. Warnings are ignored — they describe the
     * schema, not the payload.
     */
    private ErrorHandler collectInto(List<Validation.Issue> issues) {
        return new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {}

            @Override
            public void error(SAXParseException e) {
                issues.add(issue(e));
            }

            @Override
            public void fatalError(SAXParseException e) {
                issues.add(issue(e));
            }
        };
    }

    private Validation.Issue issue(SAXParseException e) {
        return new Validation.Issue(
                "/", e.getLineNumber() > 0 ? e.getLineNumber() : null, e.getMessage(), "schema");
    }

    private Validation malformed(String path, Integer line, String message) {
        return new Validation(
                false,
                Validation.Checked.SYNTAX,
                null,
                List.of(new Validation.Issue(path, line, message, "syntax")));
    }

    /**
     * How much of what the schema declares is actually filled in.
     *
     * <p>The number a reader wants is "is this mock a stub or a realistic response", so it counts
     * declared properties present anywhere in the tree, not just at the root — a response whose top
     * level is complete but whose nested objects are empty is not a complete response.
     *
     * <p>Arrays count their first element only. A list of ten fully-populated items is no more
     * complete than a list of one, and weighting by length would make completeness a function of
     * how much sample data someone pasted.
     */
    private Integer completeness(JsonNode schema, JsonNode instance) {
        Tally tally = new Tally();
        count(schema, instance, tally);
        return tally.declared == 0 ? null : Math.round(100f * tally.present / tally.declared);
    }

    private void count(JsonNode schema, JsonNode instance, Tally tally) {
        if (schema == null || !schema.isObject()) {
            return;
        }

        JsonNode properties = schema.get("properties");
        if (properties instanceof ObjectNode declared && instance != null && instance.isObject()) {
            declared.properties()
                    .forEach(
                            property -> {
                                tally.declared++;
                                JsonNode value = instance.get(property.getKey());
                                if (value != null && !value.isNull()) {
                                    tally.present++;
                                    count(property.getValue(), value, tally);
                                }
                            });
        }

        JsonNode items = schema.get("items");
        if (items != null && instance instanceof ArrayNode array && !array.isEmpty()) {
            count(items, array.get(0), tally);
        }
    }

    private static final class Tally {
        private int declared;
        private int present;
    }

    /**
     * Rewrites OpenAPI 3.0's {@code nullable: true} into the {@code type: [x, "null"]} that JSON
     * Schema 2020-12 understands.
     *
     * <p>Without it every 3.0 contract's optional-but-present-as-null field reports as a type
     * error, and the mocks that would be flagged are the correct ones. A no-op on 3.1 documents,
     * which never carry the keyword.
     */
    private JsonNode nullableToUnion(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.path("nullable").asBoolean(false) && object.get("type") != null && object.get("type").isTextual()) {
                ArrayNode union = JSON.createArrayNode().add(object.get("type").asText()).add("null");
                object.set("type", union);
            }
            object.remove("nullable");
            new ArrayList<>(object.propertyStream().toList()).forEach(entry -> nullableToUnion(entry.getValue()));
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::nullableToUnion);
        }
        return node;
    }
}
