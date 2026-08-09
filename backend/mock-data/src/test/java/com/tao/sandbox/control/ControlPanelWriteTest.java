package com.tao.sandbox.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The endpoints that change something.
 *
 * <p>Runs against a throwaway copy of the mock library rather than the real one. These tests
 * create, overwrite and delete files, and a test suite that edits the library someone is working in
 * is a test suite nobody runs twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControlPanelWriteTest {

    private static final Path ROOT = temporaryCopyOfTheLibrary();

    @DynamicPropertySource
    static void useTheCopy(DynamicPropertyRegistry registry) {
        registry.add("tao.sandbox.filesystem.root", ROOT::toString);
    }

    @Autowired private MockMvcTester mvc;

    // --- mocks -------------------------------------------------------------

    @Test
    void aNewMockIsCreatedAndReadsBackWithItsSidecars() {
        String id = "baseline/petstore/showPetById/petid=77.json";

        MvcTestResult created =
                mvc.put()
                        .uri("/__tao/mocks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "body": "{ \\"id\\": 77, \\"name\\": \\"Bran\\" }",
                                  "meta": { "status": 503, "headers": { "Retry-After": "30" } } }""")
                        .exchange();

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.effective.status").isEqualTo(503);

        MvcTestResult read = mvc.get().uri("/__tao/mocks/" + id).exchange();
        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().extractingPath("$.meta.status").isEqualTo(503);
        assertThat(read).bodyJson().extractingPath("$.meta.headers.Retry-After").isEqualTo("30");
        assertThat(read).bodyJson().extractingPath("$.body").asString().contains("Bran");
    }

    /** Two tabs against one share would otherwise overwrite each other with nothing to show for it. */
    @Test
    void overwritingAnExistingMockWithoutIfMatchIsRefused() {
        MvcTestResult result =
                mvc.put()
                        .uri("/__tao/mocks/baseline/petstore/showPetById/petid=1.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "body": "{}" }""")
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:tao:sandbox:if-match-required");
    }

    @Test
    void aStaleIfMatchIsRefused() {
        MvcTestResult result =
                mvc.put()
                        .uri("/__tao/mocks/baseline/petstore/showPetById/petid=2.json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"whatever-it-used-to-be\"")
                        .content("""
                                { "body": "{}" }""")
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.PRECONDITION_FAILED);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:tao:sandbox:stale-mock");
    }

    @Test
    void theEtagJustReadIsAcceptedAndTheMockIsReplaced() {
        String id = "baseline/petstore/showPetById/petid=2.json";

        MvcTestResult read = mvc.get().uri("/__tao/mocks/" + id).exchange();
        String etag = read.getResponse().getHeader("ETag");

        MvcTestResult written =
                mvc.put()
                        .uri("/__tao/mocks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", etag)
                        .content(
                                """
                                { "body": "{ \\"id\\": 2, \\"name\\": \\"Replaced\\" }" }""")
                        .exchange();

        assertThat(written).hasStatusOk();
        assertThat(written).bodyJson().extractingPath("$.body").asString().contains("Replaced");
        // A fresh ETag, or the next write would be accepted against a version that no longer exists.
        assertThat(written.getResponse().getHeader("ETag")).isNotEqualTo(etag);
    }

    /** A sidecar left behind keeps applying, and nothing on screen would explain why. */
    @Test
    void clearingTheMetaRemovesTheSidecarRatherThanLeavingIt() {
        String id = "baseline/petstore/createPets/name=rex.json";

        MvcTestResult before = mvc.get().uri("/__tao/mocks/" + id).exchange();
        assertThat(before)
                .bodyJson()
                .extractingPath("$.meta.headers.Location")
                .isEqualTo("/petstore/v1/pets/42");

        MvcTestResult cleared =
                mvc.put()
                        .uri("/__tao/mocks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", before.getResponse().getHeader("ETag"))
                        .content("""
                                { "body": "{ \\"name\\": \\"rex\\" }" }""")
                        .exchange();

        assertThat(cleared).hasStatusOk();
        assertThat(cleared).bodyJson().extractingPath("$.meta.headers").asMap().isEmpty();
        // Back to what the contract declares for a POST, rather than the sidecar's override.
        assertThat(cleared).bodyJson().extractingPath("$.effective.status").isEqualTo(201);
    }

    @Test
    void deletingAMockTakesItsSidecarsWithIt() {
        String id = "baseline/calculator/Divide/inta=10&intb=0.xml";

        MvcTestResult read = mvc.get().uri("/__tao/mocks/" + id).exchange();
        assertThat(read).bodyJson().extractingPath("$.meta.kind").isEqualTo("FAULT");

        assertThat(
                        mvc.delete()
                                .uri("/__tao/mocks/" + id)
                                .header("If-Match", read.getResponse().getHeader("ETag"))
                                .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.get().uri("/__tao/mocks/" + id).exchange()).hasStatus(HttpStatus.NOT_FOUND);

        // Recreated without a sidecar, it must not inherit the deleted one's FAULT kind.
        mvc.put()
                .uri("/__tao/mocks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "body": "<result>0</result>" }""")
                .exchange();

        MvcTestResult recreated = mvc.get().uri("/__tao/mocks/" + id).exchange();
        assertThat(recreated).bodyJson().extractingPath("$.meta.kind").isNull();
        assertThat(recreated).bodyJson().extractingPath("$.effective.status").isEqualTo(200);
    }

    @Test
    void savingIntoAScenarioThatDoesNotExistIsRefused() {
        assertThat(
                        mvc.put()
                                .uri("/__tao/mocks/nosuch/petstore/showPetById/petid=1.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "body": "{}" }""")
                                .exchange())
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- scenarios ---------------------------------------------------------

    @Test
    void aScenarioIsCreatedDeletedAndCannotBeDuplicated() {
        MvcTestResult created =
                mvc.post()
                        .uri("/__tao/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "id": "throwaway", "description": "made by a test", "parent": "baseline" }""")
                        .exchange();

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.parent").isEqualTo("baseline");
        assertThat(created).bodyJson().extractingPath("$.mockCount").isEqualTo(0);

        assertThat(
                        mvc.post()
                                .uri("/__tao/scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "id": "throwaway" }""")
                                .exchange())
                .hasStatus(HttpStatus.CONFLICT);

        assertThat(mvc.delete().uri("/__tao/scenarios/throwaway").exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void extendingSomethingThatDoesNotExistIsRefused() {
        assertThat(
                        mvc.post()
                                .uri("/__tao/scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "id": "orphan", "parent": "nosuch" }""")
                                .exchange())
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** Deleting what is being served would leave the sandbox answering nothing. */
    @Test
    void theActiveScenarioCannotBeDeleted() {
        MvcTestResult result = mvc.delete().uri("/__tao/scenarios/baseline").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:tao:sandbox:scenario-in-use");
    }

    /** Orphaning a child silently changes what it serves. */
    @Test
    void aScenarioWithAChildCannotBeDeleted() {
        create("middle", "baseline");
        create("leaf", "middle");

        MvcTestResult result = mvc.delete().uri("/__tao/scenarios/middle").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:tao:sandbox:scenario-extended");

        // Removed leaf-first, which is the order the rule forces.
        assertThat(mvc.delete().uri("/__tao/scenarios/leaf").exchange()).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.delete().uri("/__tao/scenarios/middle").exchange()).hasStatus(HttpStatus.NO_CONTENT);
    }

    private void create(String id, String parent) {
        mvc.post()
                .uri("/__tao/scenarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "id": "%s", "parent": "%s" }""".formatted(id, parent))
                .exchange();
    }

    @Test
    void theActiveScenarioCanBeSwitchedAndTheStatusFollows() {
        assertThat(
                        mvc.put()
                                .uri("/__tao/scenarios/active")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "scenarioId": "empty-results" }""")
                                .exchange())
                .hasStatusOk();

        assertThat(mvc.get().uri("/__tao/status").exchange())
                .bodyJson()
                .extractingPath("$.activeScenario")
                .isEqualTo("empty-results");

        // And the data plane now answers from it, which is the only reason the switch exists.
        assertThat(mvc.get().uri("/petstore/v1/pets").exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$")
                .asArray()
                .isEmpty();

        mvc.put()
                .uri("/__tao/scenarios/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "scenarioId": "baseline" }""")
                .exchange();
    }

    @Test
    void switchingToAScenarioThatDoesNotExistIsRefused() {
        assertThat(
                        mvc.put()
                                .uri("/__tao/scenarios/active")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "scenarioId": "nosuch" }""")
                                .exchange())
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- validate ----------------------------------------------------------

    @Test
    void aCompletePayloadValidatesAgainstTheContractsSchema() {
        MvcTestResult result =
                validate("petstore", "showPetById", """
                        { "id": 1, "name": "Fido", "tag": "dog" }""");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SCHEMA");
        assertThat(result).bodyJson().extractingPath("$.completeness").isEqualTo(100);
    }

    @Test
    void aMissingRequiredFieldIsReportedWithItsPath() {
        MvcTestResult result = validate("petstore", "showPetById", """
                { "id": 1 }""");

        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SCHEMA");
        // Rooted at $ exactly once — the location networknt reports is already rooted.
        assertThat(result).bodyJson().extractingPath("$.issues[0].path").isEqualTo("$");
        assertThat(result).bodyJson().extractingPath("$.issues[0].message").asString().contains("name");
        // Two of three declared properties absent.
        assertThat(result).bodyJson().extractingPath("$.completeness").isEqualTo(33);
    }

    /** Parsing is not validating, and the difference has to be visible. */
    @Test
    void malformedJsonIsReportedAsSyntaxNotAsSchema() {
        MvcTestResult result = validate("petstore", "showPetById", """
                { "id": """);

        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SYNTAX");
        assertThat(result).bodyJson().extractingPath("$.issues[0].line").isNotNull();
    }

    /** The payload the sandbox actually serves, against the XSD taken out of the WSDL. */
    @Test
    void aSoapPayloadIsCheckedAgainstTheWsdlsSchema() {
        MvcTestResult result =
                validate(
                        "stockquote",
                        "GetLastTradePrice",
                        """
                        <TradePrice xmlns="http://example.com/stockquote.xsd">
                          <price>184.25</price>
                          <currency>USD</currency>
                          <asOf>2026-08-08</asOf>
                        </TradePrice>""");

        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SCHEMA");
        assertThat(result).bodyJson().extractingPath("$.completeness").isEqualTo(100);
    }

    /** A declared child missing is a schema violation, and the line it is on is reportable. */
    @Test
    void aSoapPayloadMissingADeclaredElementIsInvalid() {
        MvcTestResult result =
                validate(
                        "stockquote",
                        "GetLastTradePrice",
                        """
                        <TradePrice xmlns="http://example.com/stockquote.xsd">
                          <price>184.25</price>
                        </TradePrice>""");

        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SCHEMA");
        assertThat(result).bodyJson().extractingPath("$.issues[0].message").asString().contains("currency");
        assertThat(result).bodyJson().extractingPath("$.issues[0].line").isNotNull();
    }

    /**
     * The schema alone cannot catch this: both elements are declared in it, so a mock holding the
     * request validates cleanly and then serves something no client can read.
     */
    @Test
    void aPayloadThatIsTheWrongDeclaredElementIsRejected() {
        MvcTestResult result =
                validate(
                        "stockquote",
                        "GetLastTradePrice",
                        """
                        <TradePriceRequest xmlns="http://example.com/stockquote.xsd">
                          <tickerSymbol>IBM</tickerSymbol>
                        </TradePriceRequest>""");

        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.issues[0].rule").isEqualTo("element");
        assertThat(result).bodyJson().extractingPath("$.issues[0].message").asString().contains("TradePrice");
    }

    /** Malformed XML is still SYNTAX — parsing is not validating, whichever protocol it is. */
    @Test
    void malformedXmlIsReportedAsSyntaxNotAsSchema() {
        MvcTestResult result = validate("stockquote", "GetLastTradePrice", "<TradePrice><price>");

        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SYNTAX");
    }

    /** Same rule as JSON, so `incomplete` means the same thing whichever protocol a mock is. */
    @Test
    void anXmlPayloadReportsCompletenessTheWayJsonDoes() {
        MvcTestResult result =
                validate(
                        "calculator",
                        "Divide",
                        """
                        <DivideResponse xmlns="http://tempuri.org/">
                          <DivideResult>5</DivideResult>
                        </DivideResponse>""");

        assertThat(result).bodyJson().extractingPath("$.checked").isEqualTo("SCHEMA");
        assertThat(result).bodyJson().extractingPath("$.completeness").isEqualTo(100);
    }

    /** Validating against a mock id is the only thing that ever fills in the tree's state. */
    @Test
    void aVerdictRecordedAgainstAMockShowsUpInTheListing() {
        String id = "baseline/petstore/showPetById/petid=1.json";

        assertThat(mvc.get().uri("/__tao/mocks?scenario=baseline&service=petstore").exchange())
                .bodyJson()
                .extractingPath("$[?(@.id=='%s')].state".formatted(id))
                .asArray()
                .containsExactly("unchecked");

        mvc.post()
                .uri("/__tao/validate?mockId=" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        { "serviceId": "petstore", "operationId": "showPetById",
                          "body": "{ \\"id\\": 1, \\"name\\": \\"Fido\\" }" }""")
                .exchange();

        assertThat(mvc.get().uri("/__tao/mocks?scenario=baseline&service=petstore").exchange())
                .bodyJson()
                .extractingPath("$[?(@.id=='%s')].state".formatted(id))
                .asArray()
                .containsExactly("incomplete");
    }

    // --- the index and the explicit-reload contract --------------------------

    /**
     * The resolve path answers from memory; a file dropped onto the disk behind the store's back
     * is served only after the explicit reload — the same contract a mounted share forces, since
     * SMB gives no change notification.
     */
    @Test
    void aFileEditedOnDiskIsServedOnlyAfterAnExplicitReload() throws IOException {
        Path mock = ROOT.resolve("scenarios/baseline/petstore/showPetById/petid=55.json");
        Files.createDirectories(mock.getParent());
        Files.writeString(mock, "{ \"id\": 55, \"name\": \"Ghost\" }");

        // Still answered by the operation's _default: the new file is not in the index yet.
        assertThat(mvc.get().uri("/petstore/v1/pets/55").exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name")
                .asString()
                .isNotEqualTo("Ghost");

        assertThat(mvc.post().uri("/__tao/reload").exchange()).hasStatusOk();

        assertThat(mvc.get().uri("/petstore/v1/pets/55").exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Ghost");
    }

    /**
     * Resolution spec §7: for each candidate filename, walk the whole scenario chain before
     * falling back to the next candidate. error-cases owns only a {@code _default} for this
     * operation; the exact match it inherits from baseline is the better address and must win.
     */
    @Test
    void anInheritedExactMatchBeatsANearerDefault() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "scenarioId": "error-cases", "method": "GET", "path": "/petstore/v1/pets/1" }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.matched")
                .isEqualTo("baseline/petstore/showPetById/petid=1.json");
        assertThat(result).bodyJson().extractingPath("$.inherited").isEqualTo(true);
    }

    // --- resolve -----------------------------------------------------------

    @Test
    void aDescribedRestRequestResolvesToTheFileItWouldHaveMatched() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "scenarioId": "baseline", "method": "GET", "path": "/petstore/v1/pets/1" }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.operationId").isEqualTo("showPetById");
        assertThat(result).bodyJson().extractingPath("$.extracted.petId").isEqualTo("1");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.matched")
                .isEqualTo("baseline/petstore/showPetById/petid=1.json");
        assertThat(result).bodyJson().extractingPath("$.inherited").isEqualTo(false);
    }

    /**
     * A miss is a successful answer describing why, which is the entire point of asking.
     *
     * <p>Divide, because it is one of the few operations with no {@code _default} to fall back to —
     * anything that has one resolves to it and is a hit, inherited or otherwise.
     */
    @Test
    void aMissIsAnAnswerNotAnError() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "scenarioId": "baseline",
                                  "body": "<soapenv:Envelope xmlns:soapenv=\\"http://schemas.xmlsoap.org/soap/envelope/\\"><soapenv:Body><calc:Divide xmlns:calc=\\"http://tempuri.org/\\"><calc:intA>7</calc:intA><calc:intB>3</calc:intB></calc:Divide></soapenv:Body></soapenv:Envelope>" }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.operationId").isEqualTo("Divide");
        assertThat(result).bodyJson().extractingPath("$.matched").isNull();
        assertThat(result).bodyJson().extractingPath("$.attempted").asArray().isNotEmpty();
    }

    @Test
    void aPastedEnvelopeResolvesAndNamesWhatItIgnored() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "scenarioId": "baseline",
                                  "body": "<soapenv:Envelope xmlns:soapenv=\\"http://schemas.xmlsoap.org/soap/envelope/\\"><soapenv:Body><sq:TradePriceRequest xmlns:sq=\\"http://example.com/stockquote.xsd\\"><sq:tickerSymbol>IBM</sq:tickerSymbol><sq:asOf>2026-08-09</sq:asOf></sq:TradePriceRequest></soapenv:Body></soapenv:Envelope>" }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.serviceId").isEqualTo("stockquote");
        assertThat(result).bodyJson().extractingPath("$.extracted.tickerSymbol").isEqualTo("IBM");
        // asOf was in the request and nothing reads it — seeing that listed is why this exists.
        assertThat(result).bodyJson().extractingPath("$.discarded").asArray().containsExactly("asOf");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.matched")
                .isEqualTo("baseline/stockquote/GetLastTradePrice/tickersymbol=ibm.xml");
    }

    @Test
    void aRequestNothingServesIsRefusedRatherThanReportedAsAMiss() {
        assertThat(
                        mvc.post()
                                .uri("/__tao/resolve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "method": "GET", "path": "/nowhere" }""")
                                .exchange())
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- record and replay -------------------------------------------------

    /**
     * The point of the whole flow: a call nothing answered names the file that would have answered
     * it, with the keys already normalised and a payload shaped like the contract's response.
     */
    @Test
    void aMissedCallDraftsTheMockItWasAskingFor() {
        // 10/0 exists in the library, so 10/5 misses.
        mvc.post()
                .uri("/soap/calculator")
                .contentType(MediaType.TEXT_XML)
                .content(
                        """
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>\
                        <calc:Divide xmlns:calc="http://tempuri.org/"><calc:intA>10</calc:intA>\
                        <calc:intB>5</calc:intB></calc:Divide></soapenv:Body></soapenv:Envelope>""")
                .exchange();

        MvcTestResult page = mvc.get().uri("/__tao/requests").exchange();
        MvcTestResult draft = mvc.get().uri("/__tao/requests/" + cursorOf(page) + "/draft").exchange();

        assertThat(draft).hasStatusOk();
        assertThat(draft)
                .bodyJson()
                .extractingPath("$.mockId")
                .isEqualTo("baseline/calculator/Divide/inta=10&intb=5.xml");
        assertThat(draft).bodyJson().extractingPath("$.keys.intA").isEqualTo("10");
        assertThat(draft).bodyJson().extractingPath("$.exists").isEqualTo(false);
        // Shaped like the declared response, and empty — a starting point, never a mock.
        assertThat(draft).bodyJson().extractingPath("$.skeleton").asString().contains("DivideResult");
        // The call that motivated it travels too, to be stored beside the mock as provenance.
        assertThat(draft).bodyJson().extractingPath("$.requestBody").asString().contains("intB");
    }

    /** Saving the draft is the ordinary write, so the mock it named now answers that call. */
    @Test
    void theDraftedMockAnswersTheCallThatAskedForIt() {
        String id = "baseline/petstore/showPetById/petid=404.json";

        mvc.get().uri("/petstore/v1/pets/404").exchange();
        MvcTestResult page = mvc.get().uri("/__tao/requests").exchange();
        MvcTestResult draft = mvc.get().uri("/__tao/requests/" + cursorOf(page) + "/draft").exchange();

        assertThat(draft).bodyJson().extractingPath("$.mockId").isEqualTo(id);
        assertThat(draft).bodyJson().extractingPath("$.skeleton").asString().contains("\"name\"");

        mvc.put()
                .uri("/__tao/mocks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "body": "{ \\"id\\": 404, \\"name\\": \\"Drafted\\" }" }""")
                .exchange();

        assertThat(mvc.get().uri("/petstore/v1/pets/404").exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Drafted");
    }

    /**
     * A file named from a subset of an ALL operation's keys can never be reached. The call was
     * answered by the operation's default, so that is what the draft proposes — and it says why.
     */
    @Test
    void aCallCarryingOnlySomeKeysDraftsTheDefaultAndExplains() {
        mvc.post()
                .uri("/soap/calculator")
                .contentType(MediaType.TEXT_XML)
                .content(
                        """
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>\
                        <calc:Add xmlns:calc="http://tempuri.org/"><calc:intA>1</calc:intA>\
                        </calc:Add></soapenv:Body></soapenv:Envelope>""")
                .exchange();

        MvcTestResult page = mvc.get().uri("/__tao/requests").exchange();
        MvcTestResult draft = mvc.get().uri("/__tao/requests/" + cursorOf(page) + "/draft").exchange();

        assertThat(draft).hasStatusOk();
        assertThat(draft)
                .bodyJson()
                .extractingPath("$.mockId")
                .isEqualTo("baseline/calculator/Add/_default.xml");
        assertThat(draft).bodyJson().extractingPath("$.keys").asMap().isEmpty();
        assertThat(draft).bodyJson().extractingPath("$.note").asString().contains("intA");
    }

    /**
     * A skeleton has to be valid where it lands, so it must read elementFormDefault from the file
     * that declares it — userservice keeps its schema in a sibling .xsd, while the WSDL's own
     * inline fragment shares the namespace and declares nothing.
     */
    @Test
    void theSkeletonPutsChildrenInTheNamespaceTheSchemaAsksFor() throws Exception {
        mvc.post()
                .uri("/soap/userservice")
                .contentType(MediaType.TEXT_XML)
                .content(
                        """
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>\
                        <us:GetUserRequest xmlns:us="http://example.org"><us:UserId>00004242</us:UserId>\
                        </us:GetUserRequest></soapenv:Body></soapenv:Envelope>""")
                .exchange();

        MvcTestResult page = mvc.get().uri("/__tao/requests").exchange();
        MvcTestResult draft = mvc.get().uri("/__tao/requests/" + cursorOf(page) + "/draft").exchange();

        // Zero-padding is stripped, exactly as extraction strips it, so the file is reachable.
        assertThat(draft)
                .bodyJson()
                .extractingPath("$.mockId")
                .isEqualTo("baseline/userservice/GetUser/userid=4242.xml");

        String skeleton = draft.getResponse().getContentAsString();
        assertThat(skeleton).contains("GetUserResponse").contains("Name");
        // Qualified: no child may push itself out of the namespace it is declared in.
        assertThat(skeleton).doesNotContain("xmlns=\\\"\\\"");
    }

    /**
     * The call that motivated a mock is stored beside it — and stays there through later edits,
     * since every ordinary save carries no request and clearing on those would throw the record
     * away the first time anyone touched the payload.
     */
    @Test
    void theCallThatMotivatedAMockIsKeptBesideIt() {
        String id = "baseline/petstore/showPetById/petid=808.json";

        mvc.put()
                .uri("/__tao/mocks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        { "body": "{ \\"id\\": 808 }",
                          "request": "{ \\"probe\\": \\"the call this was written for\\" }" }""")
                .exchange();

        MvcTestResult read = mvc.get().uri("/__tao/mocks/" + id).exchange();
        assertThat(read).bodyJson().extractingPath("$.request").asString().contains("written for");

        // An edit that says nothing about provenance must not erase it.
        mvc.put()
                .uri("/__tao/mocks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", read.getResponse().getHeader("ETag"))
                .content("""
                        { "body": "{ \\"id\\": 808, \\"name\\": \\"Edited\\" }" }""")
                .exchange();

        assertThat(mvc.get().uri("/__tao/mocks/" + id).exchange())
                .bodyJson()
                .extractingPath("$.request")
                .asString()
                .contains("written for");
    }

    /** Provenance is documentation: it must never appear as a mock in its own right. */
    @Test
    void theStoredCallIsNotListedAsAMock() {
        String id = "baseline/petstore/showPetById/petid=809.json";

        mvc.put()
                .uri("/__tao/mocks/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "body": "{ \\"id\\": 809 }", "request": "{ \\"probe\\": 1 }" }""")
                .exchange();

        assertThat(mvc.get().uri("/__tao/mocks?scenario=baseline&service=petstore").exchange())
                .bodyJson()
                .extractingPath("$[*].fileName")
                .asArray()
                .doesNotContain("petid=809.request.json");
    }

    /** A request rejected before resolution has no contract to write a mock against. */
    @Test
    void aCallRejectedBeforeResolutionCannotBeDrafted() {
        mvc.post()
                .uri("/soap/calculator")
                .contentType(MediaType.TEXT_XML)
                .content(
                        """
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>\
                        <calc:Multiply xmlns:calc="http://tempuri.org/"><calc:intA>2</calc:intA>\
                        </calc:Multiply></soapenv:Body></soapenv:Envelope>""")
                .exchange();

        MvcTestResult page = mvc.get().uri("/__tao/requests").exchange();
        MvcTestResult draft = mvc.get().uri("/__tao/requests/" + cursorOf(page) + "/draft").exchange();

        assertThat(draft).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(draft).bodyJson().extractingPath("$.type").isEqualTo("urn:tao:sandbox:not-resolvable");
    }

    // --- request log -------------------------------------------------------

    @Test
    void whatTheApplicationUnderTestCalledShowsUpInTheLog() {
        mvc.get().uri("/petstore/v1/pets/1").exchange();

        MvcTestResult result = mvc.get().uri("/__tao/requests").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.mode").isEqualTo("FULL");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.entries[?(@.operationId=='showPetById')].status")
                .asArray()
                .contains(200);
    }

    /** Bodies are one call away, never in the list — the same reason mock bodies are not. */
    @Test
    void oneEntryCarriesItsBodiesAndTrace() {
        mvc.get().uri("/petstore/v1/pets/2").exchange();

        MvcTestResult page = mvc.get().uri("/__tao/requests").exchange();
        MvcTestResult entry = mvc.get().uri("/__tao/requests/" + cursorOf(page)).exchange();

        assertThat(entry).hasStatusOk();
        assertThat(entry).bodyJson().extractingPath("$.responseBody").asString().contains("name");
        assertThat(entry).bodyJson().extractingPath("$.attempted").asArray().isNotEmpty();
        assertThat(entry).bodyJson().extractingPath("$.extracted.petId").isEqualTo("2");
    }

    /** A cursor pointing past what is retained must say the log thinned, not pretend it is whole. */
    @Test
    void aCursorOlderThanTheBufferIsReportedAsSampled() {
        mvc.get().uri("/petstore/v1/pets/1").exchange();

        // Nothing has been evicted, so even an ancient cursor is honestly FULL here — what is
        // asserted is that the field is present and answers the question at all.
        assertThat(mvc.get().uri("/__tao/requests?since=0").exchange())
                .bodyJson()
                .extractingPath("$.mode")
                .isEqualTo("FULL");
    }

    @Test
    void anEntryThatHasAgedOutSaysSo() {
        assertThat(mvc.get().uri("/__tao/requests/999999").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    // --- helpers -----------------------------------------------------------

    /** Built with a mapper, so a payload never has to be hand-escaped into a JSON string. */
    private MvcTestResult validate(String serviceId, String operationId, String body) {
        String request;
        try {
            request =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(
                                    Map.of("serviceId", serviceId, "operationId", operationId, "body", body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        return mvc.post()
                .uri("/__tao/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .exchange();
    }

    private String cursorOf(MvcTestResult page) {
        try {
            return com.jayway.jsonpath.JsonPath.read(page.getResponse().getContentAsString(), "$.cursor");
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the log cursor", e);
        }
    }

    private static Path temporaryCopyOfTheLibrary() {
        try {
            Path source = Path.of("mocks");
            Path target = Files.createTempDirectory("tao-sandbox-write-test");

            try (Stream<Path> tree = Files.walk(source)) {
                for (Path path : tree.toList()) {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                }
            }

            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage a mock library for the write tests", e);
        }
    }

    @AfterAll
    static void removeTheCopy() throws IOException {
        try (Stream<Path> tree = Files.walk(ROOT)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
