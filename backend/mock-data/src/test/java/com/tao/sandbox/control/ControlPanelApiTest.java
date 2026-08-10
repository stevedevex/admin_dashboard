package com.tao.sandbox.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The control-panel contract, exercised against the sandbox's own configuration.
 *
 * <p>Against the real specs and the real mock library rather than fixtures, because most of what
 * these endpoints assert is that the two agree — that a name computed for {@code GetLastTradePrice}
 * is the name of a file the resolver would actually find. A fixture would let the two drift and
 * still pass.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControlPanelApiTest {

    @Autowired private MockMvcTester mvc;

    @Test
    void statusDescribesTheSandbox() {
        MvcTestResult result = mvc.get().uri("/__tao/status").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.store").isEqualTo("FILESYSTEM");
        assertThat(result).bodyJson().extractingPath("$.activeScenario").isEqualTo("baseline");
        assertThat(result).bodyJson().extractingPath("$.scenarioHeader").isEqualTo("X-Sandbox-Scenario");
        assertThat(result).bodyJson().extractingPath("$.serviceCount").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$.startupProblems").asArray().isEmpty();
        assertThat(result).bodyJson().extractingPath("$.root").asString().endsWith("mocks");
    }

    /**
     * Without this a browser applies heuristic freshness to an ETagged response and reuses it
     * without asking — the dashboard then shows a verdict it has already superseded. It must be
     * {@code no-store} rather than {@code no-cache}: the ETag covers the payload, not the
     * validation verdict beside it, so a conditional request would answer 304 and replay the
     * stale verdict quite correctly. See {@link ControlPanelCacheControl}.
     */
    @Test
    void controlPanelAnswersMustBeRevalidatedRatherThanReused() {
        assertThat(mvc.get().uri("/__tao/status").exchange().getResponse().getHeader("Cache-Control"))
                .isEqualTo("no-store");
        assertThat(
                        mvc.get()
                                .uri("/__tao/mocks/baseline/petstore/showPetById/petid=1.json")
                                .exchange()
                                .getResponse()
                                .getHeader("Cache-Control"))
                .isEqualTo("no-store");
    }

    /** The data plane speaks its upstream's language, headers included — we add nothing to it. */
    @Test
    void theDataPlaneKeepsItsOwnCachingBehaviour() {
        assertThat(mvc.get().uri("/petstore/v1/pets/1").exchange().getResponse().getHeader("Cache-Control"))
                .isNull();
    }

    /** One call for the dashboard's headline numbers; counting is the server's job. */
    @Test
    void theSummaryCarriesTheDashboardsHeadlineNumbers() {
        MvcTestResult result = mvc.get().uri("/__tao/summary").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.serviceCount").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$.scenarioCount").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.activeScenarioId").isEqualTo("baseline");
        assertThat(result).bodyJson().extractingPath("$.mockCount").asNumber().satisfies(
                count -> assertThat(count.intValue()).isGreaterThan(10));
        assertThat(result).bodyJson().extractingPath("$.largestMockBytes").asNumber().satisfies(
                bytes -> assertThat(bytes.longValue()).isGreaterThan(0));
    }

    /**
     * A reload checks the library, so the counts it leaves behind describe the files rather than
     * the clicking. Nothing is left unchecked afterwards, which is what makes the invalid and
     * incomplete counts beside it worth reading: a zero there now means "checked, and clean",
     * where before it could equally have meant "nobody looked".
     *
     * <p>The unchecked count still earns its place — startup validation can be turned off, and it
     * is off in these tests, so between boot and the first reload every mock reads unchecked.
     */
    /**
     * A payload that declares itself an error is not the shape the contract describes, and must not
     * be judged against it.
     *
     * <p>Both of the library's deliberate error mocks were reported invalid the first time anything
     * checked the whole library — a SOAP fault against the success element, and a 503
     * {@code problem+json} against the pet schema. Both files are correct; the check was not. A
     * checker that marks correct files broken is one people learn to ignore, so this stays pinned.
     *
     * <p>They read unchecked rather than valid: nothing assessed their shape, and saying otherwise
     * would claim an assurance nobody has.
     */
    @Test
    void aMockDeclaringItselfAnErrorIsNotJudgedAgainstTheSuccessSchema() {
        assertThat(mvc.post().uri("/__tao/reload").exchange()).hasStatusOk();

        assertThat(mvc.get().uri("/__tao/mocks?scenario=error-cases&service=petstore").exchange())
                .bodyJson()
                .extractingPath("$[?(@.id=='error-cases/petstore/showPetById/_default.json')].state")
                .asArray()
                .containsExactly("unchecked");

        assertThat(mvc.get().uri("/__tao/mocks?scenario=baseline&service=calculator").exchange())
                .bodyJson()
                .extractingPath("$[?(@.id=='baseline/calculator/Divide/inta=10&intb=0.xml')].state")
                .asArray()
                .containsExactly("unchecked");

        // And an ordinary success payload is still judged, so the rule above narrows nothing else.
        assertThat(mvc.get().uri("/__tao/mocks?scenario=baseline&service=petstore").exchange())
                .bodyJson()
                .extractingPath("$[?(@.id=='baseline/petstore/showPetById/petid=1.json')].state")
                .asArray()
                .containsExactly("valid");
    }

    @Test
    void reloadingChecksTheLibraryRatherThanLeavingItUnchecked() {
        assertThat(mvc.post().uri("/__tao/reload").exchange()).hasStatusOk();

        MvcTestResult result = mvc.get().uri("/__tao/summary").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.invalidCount").isEqualTo(0);

        // Most of the library is judged against a schema. Not all of it: a payload declaring
        // itself an error is deliberately not the shape the contract describes, so nothing checks
        // it and it stays unchecked — which is the honest answer, not a clean one.
        assertThat(result)
                .bodyJson()
                .extractingPath("$.uncheckedCount")
                .asNumber()
                .satisfies(unchecked -> assertThat(unchecked.intValue()).isLessThan(5));
        assertThat(result)
                .bodyJson()
                .extractingPath("$.mockCount")
                .asNumber()
                .satisfies(count -> assertThat(count.intValue()).isGreaterThan(10));
    }

    /** hasSchema and the mock count are server decisions the dashboard must not re-derive. */
    @Test
    void servicesCarrySchemaAvailabilityFormatAndMockCount() {
        MvcTestResult result = mvc.get().uri("/__tao/services").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='petstore')].hasSchema")
                .asArray()
                .containsExactly(true);
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='petstore')].format")
                .asArray()
                .containsExactly("json");
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='stockquote')].format")
                .asArray()
                .containsExactly("xml");
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='petstore')].mockCount")
                .asArray()
                .satisfies(counts -> assertThat(((Number) counts[0]).intValue()).isGreaterThan(0));
    }

    /** serviceIds says what a scenario covers, so inherited coverage counts. */
    @Test
    void scenariosNameTheServicesTheyCover() {
        MvcTestResult result = mvc.get().uri("/__tao/scenarios").exchange();

        assertThat(result).hasStatusOk();
        // empty-results owns two mocks but covers everything baseline covers.
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='empty-results')].serviceIds[*]")
                .asArray()
                .contains("petstore", "stockquote", "calculator");
    }

    /** The client team fetches what they integrate against from the same host they call. */
    @Test
    void theDroppedInContractIsServedVerbatim() throws Exception {
        MvcTestResult rest = mvc.get().uri("/__tao/services/petstore/spec").exchange();
        assertThat(rest).hasStatusOk();
        assertThat(rest.getResponse().getContentType()).isEqualTo("application/yaml");
        assertThat(rest.getResponse().getContentAsString()).contains("openapi:").contains("listPets");
        // The served contract points at the sandbox mount — the ?wsdl rule, applied to REST. A
        // client resolving its endpoint from this document must land here, not on production.
        assertThat(rest.getResponse().getContentAsString()).contains("url: \"/petstore/v1\"");

        MvcTestResult soap = mvc.get().uri("/__tao/services/stockquote/spec").exchange();
        assertThat(soap).hasStatusOk();
        assertThat(soap.getResponse().getContentType()).startsWith("text/xml");
        assertThat(soap.getResponse().getContentAsString()).contains("definitions").contains("GetLastTradePrice");

        assertThat(mvc.get().uri("/__tao/services/nosuch/spec").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    /**
     * The dashboard renders an input per key and sends the values back to {@code /mocks/name}. It
     * can only do that if the key arrives already named — deriving {@code tickerSymbol} from the
     * XPath is the server's job, done once.
     */
    @Test
    void servicesNameTheirKeysRatherThanOnlyDeclaringThem() {
        MvcTestResult result = mvc.get().uri("/__tao/services").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='stockquote')].operations[0].keys[0].name")
                .asArray()
                .containsExactly("tickerSymbol");
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='stockquote')].operations[0].keys[0].source")
                .asArray()
                .containsExactly("XPATH");
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='petstore')].endpoint")
                .asArray()
                .containsExactly("/petstore/v1");
    }

    @Test
    void schemaIsReturnedWithItsRefsInlined() {
        MvcTestResult result =
                mvc.get().uri("/__tao/services/petstore/operations/showPetById/schema").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.format").isEqualTo("JSON");
        assertThat(result).bodyJson().extractingPath("$.available").isEqualTo(true);
        // A $ref here would be unresolvable to a caller never given the components section.
        assertThat(result)
                .bodyJson()
                .extractingPath("$.schema")
                .asString()
                .contains("\"name\"")
                .doesNotContain("$ref");
    }

    /**
     * Taken out of the WSDL's inline {@code <wsdl:types>}, with the prefixes it relies on carried
     * down from the root — {@code xsd1} is bound on {@code <definitions>}, and a schema lifted out
     * without it does not compile.
     */
    @Test
    void aSoapOperationReturnsTheSchemaFromItsWsdl() {
        MvcTestResult result =
                mvc.get().uri("/__tao/services/stockquote/operations/GetLastTradePrice/schema").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.format").isEqualTo("XSD");
        assertThat(result).bodyJson().extractingPath("$.available").isEqualTo(true);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.schema")
                .asString()
                .contains("TradePrice")
                .contains("http://example.com/stockquote.xsd");
    }

    /** No schema is a state, not a failure — and it has to say why, or it reads as a defect. */
    @Test
    void anOperationWithNoDeclaredBodySaysWhyRatherThanJustNo() {
        MvcTestResult result =
                mvc.get().uri("/__tao/services/tictactoe/operations/get-board/schema").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.available").isNotNull();
    }

    @Test
    void anUnknownOperationIsAProblemNamingTheOnesThatExist() {
        MvcTestResult result =
                mvc.get().uri("/__tao/services/petstore/operations/nosuchop/schema").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:tao:sandbox:operation-not-found");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("showPetById");
    }

    /** A child scenario owns its overrides, not everything it can serve. */
    @Test
    void scenarioMockCountsExcludeWhatIsInherited() {
        MvcTestResult result = mvc.get().uri("/__tao/scenarios").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='empty-results')].parent")
                .asArray()
                .containsExactly("baseline");
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.id=='empty-results')].mockCount")
                .asArray()
                .containsExactly(2);
    }

    @Test
    void listingAScenarioIncludesWhatItInheritsFlagged() {
        MvcTestResult result =
                mvc.get().uri("/__tao/mocks?scenario=empty-results&service=petstore").exchange();

        assertThat(result).hasStatusOk();
        // The override this scenario exists for…
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.operationId=='listPets' && @.fileName=='_default.json')].inherited")
                .asArray()
                .containsExactly(false);
        // …and its sibling in the same directory, which the override does not displace. Only the
        // slot is overridden, not the operation, so limit=1 still resolves through the parent.
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.operationId=='listPets' && @.fileName=='limit=1.json')].inherited")
                .asArray()
                .containsExactly(true);
        // …and showPetById, which only baseline stores.
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.operationId=='showPetById')].inheritedFrom")
                .asArray()
                .containsOnly("baseline");
        // Present and one of the declared states. Not pinned to a value: verdicts live in a bean
        // shared by every test in this context, and whether one has been reached by now depends on
        // which tests ran first. What this test is about is the inheritance flags above.
        assertThat(result)
                .bodyJson()
                .extractingPath("$[0].state")
                .asString()
                .isIn("unchecked", "valid", "incomplete", "invalid");
    }

    @Test
    void anUnknownScenarioIsAProblemRatherThanAnEmptyList() {
        MvcTestResult result = mvc.get().uri("/__tao/mocks?scenario=nosuch").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("urn:tao:sandbox:scenario-not-found");
    }

    /**
     * The 201 is in the OpenAPI document and nowhere else. An author has to be able to see that,
     * which means seeing an empty sidecar and a 201 side by side.
     */
    @Test
    void effectiveStatusComesFromTheContractWhenTheSidecarIsSilent() {
        MvcTestResult result =
                mvc.get().uri("/__tao/mocks/baseline/petstore/createPets/_default.json").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.meta.status").isNull();
        assertThat(result).bodyJson().extractingPath("$.effective.status").isEqualTo(201);
        assertThat(result).bodyJson().extractingPath("$.effective.contentType").isEqualTo("application/json");
        assertThat(result).bodyJson().extractingPath("$.body").asString().isNotEmpty();
    }

    /** A sidecar outranks the contract, and both readings stay visible. */
    @Test
    void aSidecarOutranksTheContractAndBothStayVisible() {
        MvcTestResult result =
                mvc.get().uri("/__tao/mocks/error-cases/petstore/showPetById/_default.json").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.meta.status").isEqualTo(503);
        assertThat(result).bodyJson().extractingPath("$.meta.headers.Retry-After").isEqualTo("30");
        assertThat(result).bodyJson().extractingPath("$.effective.status").isEqualTo(503);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.effective.contentType")
                .isEqualTo("application/problem+json");
    }

    /**
     * A SOAP fault answers 500, not the 200 a response would. Getting that wrong makes a mocked
     * failure look like a success to every client that keys off the status.
     */
    @Test
    void aSoapFaultsEffectiveStatusIsNotTwoHundred() {
        MvcTestResult result =
                mvc.get().uri("/__tao/mocks/baseline/calculator/Divide/inta=10&intb=0.xml").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.meta.kind").isEqualTo("FAULT");
        assertThat(result).bodyJson().extractingPath("$.meta.status").isNull();
        assertThat(result).bodyJson().extractingPath("$.effective.status").isEqualTo(500);
    }

    @Test
    void aMockRespondsAnEtag() {
        MvcTestResult result =
                mvc.get().uri("/__tao/mocks/baseline/petstore/showPetById/petid=1.json").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader("ETag")).isNotNull().startsWith("\"");
    }

    /** A part of a mock id that is a path, rather than one directory or file, is refused. */
    @Test
    void aMockIdWhoseFileNameIsAPathIsRejected() {
        MvcTestResult result = mvc.get().uri("/__tao/mocks/baseline/petstore/showPetById/sub/dir.json").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aMissingMockIsAProblemNamingIt() {
        MvcTestResult result =
                mvc.get().uri("/__tao/mocks/baseline/petstore/showPetById/petid=999.json").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:tao:sandbox:mock-not-found");
    }

    /**
     * The name this computes must be the name the resolver looks for. {@code tickersymbol=ibm.xml}
     * exists on disk, and this is what proves the two agree.
     */
    @Test
    void aNameIsComputedTheWayARequestWouldResolve() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/mocks/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "serviceId": "stockquote",
                                  "operationId": "GetLastTradePrice",
                                  "keys": { "tickerSymbol": "IBM" } }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.fileName").isEqualTo("tickersymbol=ibm.xml");
        assertThat(result).bodyJson().extractingPath("$.normalised.tickerSymbol").isEqualTo("ibm");
    }

    /** Zero-padding is stripped, so 0000001 and 1 cannot become two files, one unreachable. */
    @Test
    void aNameIsNormalisedBeforeItBecomesAFile() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/mocks/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "serviceId": "petstore",
                                  "operationId": "showPetById",
                                  "keys": { "petId": "  0000001 " } }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.fileName").isEqualTo("petid=1.json");
    }

    /** No keys names the fallback the resolver tries last — a mock authors legitimately write. */
    @Test
    void noKeysNamesTheDefaultMock() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/mocks/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "serviceId": "petstore", "operationId": "showPetById", "keys": {} }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.fileName").isEqualTo("_default.json");
    }

    /** Divide resolves on both operands; a file named from one of them is unreachable. */
    @Test
    void aPartialKeySetIsRefusedRatherThanNamed() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/mocks/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "serviceId": "calculator",
                                  "operationId": "Divide",
                                  "keys": { "intA": "10" } }""")
                        .exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:tao:sandbox:incomplete-keys");
    }

    /** Declaration order, not the order the caller happened to send them in. */
    @Test
    void multipleKeysAreNamedInDeclarationOrder() {
        MvcTestResult result =
                mvc.post()
                        .uri("/__tao/mocks/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                { "serviceId": "calculator",
                                  "operationId": "Divide",
                                  "keys": { "intB": "0", "intA": "10" } }""")
                        .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.fileName").isEqualTo("inta=10&intb=0.xml");
    }

    @Test
    void reloadAnswersWithTheNewStatus() {
        MvcTestResult result = mvc.post().uri("/__tao/reload").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.activeScenario").isEqualTo("baseline");
    }
}
