package com.tao.sandbox.ai;

import com.tao.sandbox.ai.llm.ChatMessage;
import com.tao.sandbox.ai.llm.ChatRequest;
import com.tao.sandbox.ai.llm.ChatResponse;
import com.tao.sandbox.ai.llm.LlmClient;
import com.tao.sandbox.ai.llm.ResponseFormat;
import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.ServiceDescriptor;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.validate.MockValidator;
import com.tao.sandbox.validate.Validation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generating a payload for one operation, and checking it before offering it.
 *
 * <p>The validation is the feature. A model will produce plausible-looking XML that does not
 * satisfy the schema, and a mock that is wrong in a subtle way is worse than no mock at all — it
 * makes a client pass against a shape the real service never returns. So generation is a loop:
 * ask, check with the same validator the Validate button uses, and if the contract was broken,
 * hand the failure back with the validator's own words and ask once more.
 *
 * <p>One repair, not many. A model that has been told exactly which paths are wrong and still
 * answers wrongly is not going to be argued into correctness, and an author reading two concrete
 * issues is better served than one waiting on a fourth round trip.
 */
public class PayloadGenerator {

    /** The first answer plus one repair. See the class note on why not more. */
    private static final int MAX_ATTEMPTS = 2;

    private final SpecRegistry registry;
    private final MockValidator validator;
    private final LlmClient client;
    private final AiProperties properties;

    public PayloadGenerator(
            SpecRegistry registry, MockValidator validator, LlmClient client, AiProperties properties) {
        this.registry = registry;
        this.validator = validator;
        this.client = client;
        this.properties = properties;
    }

    /**
     * @throws IllegalArgumentException when the operation is not served, or its contract declares
     *     nothing to generate against — both of which are answers the caller should show, not
     *     faults
     */
    public PayloadGeneration generate(
            String serviceId, String operationId, String prompt, String current) {
        PayloadGenerationRequest request = describe(serviceId, operationId, prompt, current);

        ChatRequest chat =
                new ChatRequest(
                        properties.model(),
                        List.of(
                                ChatMessage.system(systemPrompt(request)),
                                ChatMessage.system(context(request)),
                                ChatMessage.user(userPrompt(request))),
                        properties.temperature(),
                        responseFormat(request));

        ChatResponse answer = client.complete(chat);
        String body = clean(answer.content());
        Validation verdict = validator.validate(serviceId, operationId, body);

        if (verdict.valid()) {
            return new PayloadGeneration(body, verdict, 1, client.name(), answer.model());
        }

        // The repair turn. The validator's issues go back verbatim — its wording names the path and
        // the rule, which is more precise than anything paraphrased here would be.
        ChatResponse second =
                client.complete(
                        chat.continuedWith(
                                ChatMessage.assistant(body), ChatMessage.user(repairPrompt(verdict))));

        String repaired = clean(second.content());
        Validation repairedVerdict = validator.validate(serviceId, operationId, repaired);

        // Keep whichever answer is actually better. Only the repair being valid makes it better:
        // both being invalid is a tie, and the first is then preferred because a model that has
        // already been told what was wrong and answered again tends to drift further from the
        // shape, not closer — so the near miss an author can edit is the one worth handing over.
        return repairedVerdict.valid()
                ? new PayloadGeneration(repaired, repairedVerdict, MAX_ATTEMPTS, client.name(), second.model())
                : new PayloadGeneration(body, verdict, MAX_ATTEMPTS, client.name(), answer.model());
    }

    /** Resolve what the contract says about this operation, or explain why nothing can be built. */
    private PayloadGenerationRequest describe(
            String serviceId, String operationId, String prompt, String current) {
        ServiceDescriptor service =
                registry.findService(serviceId)
                        .orElseThrow(() -> new IllegalArgumentException("No such service: " + serviceId));

        boolean soap = service.type() == ServiceType.SOAP;

        String schema =
                soap
                        ? registry.soapSchemas(serviceId)
                                .flatMap(schemas -> schemas.documentFor(operationId))
                                .orElse(null)
                        : registry.findResponseSchema(serviceId, operationId).orElse(null);

        String starter =
                soap
                        ? registry.soapSchemas(serviceId)
                                .flatMap(schemas -> schemas.skeleton(operationId))
                                .orElse(null)
                        : null;

        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException(
                    "%s/%s declares no response schema, so there is nothing to generate against"
                            .formatted(serviceId, operationId));
        }

        return new PayloadGenerationRequest(
                serviceId,
                operationId,
                soap ? PayloadGenerationRequest.Format.XML : PayloadGenerationRequest.Format.JSON,
                schema,
                starter,
                current,
                prompt);
    }

    private ResponseFormat responseFormat(PayloadGenerationRequest request) {
        return request.format() == PayloadGenerationRequest.Format.JSON
                ? ResponseFormat.json(request.schema())
                : ResponseFormat.xml(request.schema());
    }

    // --- prompts -----------------------------------------------------------

    /**
     * The standing instruction.
     *
     * <p>Two things are being bought here, and they pull against each other: realism, and a
     * payload the schema accepts. The schema constraints are stated as absolute and the realism as
     * a preference, because a beautiful invented field makes the mock invalid while a dull valid
     * one is merely dull — and the author can always edit dull.
     */
    private String systemPrompt(PayloadGenerationRequest request) {
        String language = request.format() == PayloadGenerationRequest.Format.JSON ? "JSON" : "XML";

        return """
        You generate mock response payloads for an API sandbox that stands in for real services.

        Output rules, in order of precedence:
        1. Answer with the %s payload and nothing else. No prose, no explanation, no markdown code \
        fences.
        2. The payload must satisfy the supplied schema exactly. Every required field present, \
        every enum one of the declared values, every format and every bound respected. Never \
        invent a field the schema does not declare.
        3. Populate every field the schema declares, not only the required ones. This payload \
        stands in for a fully populated upstream response; empty and omitted fields are what it \
        exists to avoid.
        4. Within those rules, make the data realistic and varied. Plausible names, dates and \
        identifiers rather than "string" or "foo"; different values across list elements rather \
        than the same record repeated; values that are consistent with each other within a record.
        5. Do not use real people, real companies, or anything that looks like real personal data.
        6. If the file already holds a payload it is given to you below. Judge from what is asked \
        whether it is an adjustment to that payload or a request for something different. When it \
        is an adjustment, change only what was asked for and preserve everything else exactly — \
        the values already there were chosen, and replacing them silently loses that work.
        """
                .formatted(language);
    }

    /**
     * The contract, as standing context rather than as part of what was asked for.
     *
     * <p>Keeping the schema out of the user turn is not tidiness. The user turn is the only place
     * the request itself appears, and anything reading it for intent — a count of records, most
     * obviously — would otherwise be reading the schema too, and would find whichever number the
     * contract happened to mention first.
     */
    private String context(PayloadGenerationRequest request) {
        StringBuilder context = new StringBuilder();

        context.append("Operation: ")
                .append(request.serviceId())
                .append('/')
                .append(request.operationId())
                .append("\n\nSchema the payload must satisfy:\n")
                .append(request.schema());

        if (request.starter() != null && !request.starter().isBlank()) {
            context.append("\n\nThe payload must have exactly this shape, with the values filled in:\n")
                    .append(request.starter());
        }

        if (request.current() != null && !request.current().isBlank()) {
            context.append(
                            "\n\nThe file currently holds this payload. If what is being asked for is an\n"
                                + "adjustment to it, change only what was asked and keep the rest as it is. If it is\n"
                                + "a request for something different, replace it. Decide from what is asked:\n")
                    .append(request.current());
        }

        return context.toString();
    }

    /** What the person actually asked for, and nothing else. */
    private String userPrompt(PayloadGenerationRequest request) {
        return request.prompt() == null || request.prompt().isBlank()
                ? "A representative, fully populated response."
                : request.prompt().trim();
    }

    private String repairPrompt(Validation verdict) {
        String issues =
                verdict.issues().stream()
                        .map(issue -> "- %s: %s (%s)".formatted(issue.path(), issue.message(), issue.rule()))
                        .collect(Collectors.joining("\n"));

        return """
        That payload was rejected when validated against the schema:

        %s

        Return a corrected payload. Same rules as before: the payload only, no prose, no code \
        fences, and change nothing that was not at fault.
        """
                .formatted(issues.isBlank() ? "- the payload did not satisfy the schema" : issues);
    }

    /**
     * Strip what models add regardless of instruction.
     *
     * <p>A fenced block is the overwhelmingly common one — the instruction says not to, and it
     * happens anyway, and a payload wrapped in backticks fails validation for a reason that has
     * nothing to do with the contract. Cheap to undo here; confusing to debug in the editor.
     */
    private String clean(String content) {
        if (content == null) {
            return "";
        }

        String trimmed = content.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstBreak = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) {
            return trimmed;
        }

        return trimmed.substring(firstBreak + 1, lastFence).strip();
    }
}
