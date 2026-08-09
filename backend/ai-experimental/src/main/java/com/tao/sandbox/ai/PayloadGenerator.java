package com.tao.sandbox.ai;

import com.tao.sandbox.ai.llm.ModelProvider;
import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.ServiceDescriptor;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.validate.MockValidator;
import com.tao.sandbox.validate.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

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
    private final ChatModel model;

    /**
     * The endpoint, credential, deployment and temperature every call inherits.
     *
     * <p>Carried so each request can be these plus one thing — the schema the answer must satisfy,
     * which differs per operation — rather than restating the connection on every call.
     */
    private final OpenAiChatOptions defaults;

    private final ModelProvider provider;

    public PayloadGenerator(
            SpecRegistry registry,
            MockValidator validator,
            ChatModel model,
            OpenAiChatOptions defaults,
            ModelProvider provider) {
        this.registry = registry;
        this.validator = validator;
        this.model = model;
        this.defaults = defaults;
        this.provider = provider;
    }

    /**
     * @throws IllegalArgumentException when the operation is not served, or its contract declares
     *     nothing to generate against — both of which are answers the caller should show, not
     *     faults
     */
    public PayloadGeneration generate(
            String serviceId, String operationId, String prompt, String current) {
        PayloadGenerationRequest request = describe(serviceId, operationId, prompt, current);

        List<Message> conversation =
                new ArrayList<>(
                        List.of(
                                new SystemMessage(systemPrompt(request)),
                                new SystemMessage(context(request)),
                                new UserMessage(userPrompt(request))));

        OpenAiChatOptions options = optionsFor(request);

        ChatResponse answer = model.call(new Prompt(conversation, options));
        String body = clean(contentOf(answer));
        Validation verdict = validator.validate(serviceId, operationId, body);

        if (verdict.valid()) {
            return new PayloadGeneration(body, verdict, 1, provider.name(), modelOf(answer));
        }

        // The repair turn. The validator's issues go back verbatim — its wording names the path and
        // the rule, which is more precise than anything paraphrased here would be. The rejected
        // payload goes with them as the assistant's own turn, so the model is correcting something
        // it said rather than being handed an unattributed example.
        conversation.add(new AssistantMessage(body));
        conversation.add(new UserMessage(repairPrompt(verdict)));

        ChatResponse second = model.call(new Prompt(conversation, options));

        String repaired = clean(contentOf(second));
        Validation repairedVerdict = validator.validate(serviceId, operationId, repaired);

        // Keep whichever answer is actually better. Only the repair being valid makes it better:
        // both being invalid is a tie, and the first is then preferred because a model that has
        // already been told what was wrong and answered again tends to drift further from the
        // shape, not closer — so the near miss an author can edit is the one worth handing over.
        return repairedVerdict.valid()
                ? new PayloadGeneration(
                        repaired, repairedVerdict, MAX_ATTEMPTS, provider.name(), modelOf(second))
                : new PayloadGeneration(body, verdict, MAX_ATTEMPTS, provider.name(), modelOf(answer));
    }

    /**
     * The assistant's text, or empty when there is none.
     *
     * <p>Absence is answered with empty text rather than an exception: the caller validates whatever
     * comes back, and an empty payload fails validation with a message an author can read, which is
     * more use than a null pointer from somewhere inside the response model.
     */
    private String contentOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }

        AssistantMessage output = response.getResult().getOutput();
        return output == null || output.getText() == null ? "" : output.getText();
    }

    /** What actually answered, as reported — a deployment can route somewhere else. */
    private String modelOf(ChatResponse response) {
        String reported = response == null ? null : response.getMetadata().getModel();
        return reported == null || reported.isBlank() ? defaults.getModel() : reported;
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

    /**
     * The connection defaults, plus the shape this particular answer must take.
     *
     * <p>Structured outputs are the single largest lever on whether generation succeeds: a provider
     * honouring {@code json_schema} cannot return a payload the schema rejects, which turns the
     * repair loop into a fallback rather than the main path.
     *
     * <p>XSD has no equivalent in any chat API, so an XML request declares no format at all and
     * relies on the schema being in the prompt — and on the validator to catch what comes back
     * regardless. Mutating rather than building afresh keeps the endpoint, credential and
     * deployment attached; a bare options object here would be a request to nowhere.
     */
    private OpenAiChatOptions optionsFor(PayloadGenerationRequest request) {
        if (request.format() != PayloadGenerationRequest.Format.JSON) {
            return defaults;
        }

        return defaults.mutate()
                .responseFormat(
                        OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                                .jsonSchema(request.schema())
                                .build())
                .build();
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
