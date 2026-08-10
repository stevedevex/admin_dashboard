package com.tao.sandbox.runtime.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.spec.OperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapOperationDefinition;
import com.tao.sandbox.spec.wsdl.SoapServiceDefinition;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;

/**
 * Which operation a request is for — the step before resolution, and the one that used to be
 * answered in three places.
 *
 * <p>All inputs are hand-built, so nothing here depends on a contract, a registry or a Spring
 * context. That is the point of the class being static: the rule can be stated and checked on its
 * own, rather than only through whichever endpoint happens to exercise it.
 */
class OperationLocatorTest {

    private static final String NS = "http://example.org/test";

    // --- REST ---------------------------------------------------------------

    @Test
    void matchesAPathTemplateAndHandsBackWhatItCaptured() {
        var match =
                OperationLocator.forRest(List.of(rest("getOrder", HttpMethod.GET, "/orders/{orderId}")), "GET", path("/orders/42"))
                        .orElseThrow();

        assertThat(match.operation().operationId()).isEqualTo("getOrder");
        assertThat(match.pathVariables()).containsExactlyEntriesOf(Map.of("orderId", "42"));
    }

    @Test
    void capturesEveryVariableInAMultiSegmentTemplate() {
        var match =
                OperationLocator.forRest(
                                List.of(rest("getCell", HttpMethod.GET, "/board/{row}/{column}")),
                                "GET",
                                path("/board/1/2"))
                        .orElseThrow();

        assertThat(match.pathVariables()).containsEntry("row", "1").containsEntry("column", "2");
    }

    /**
     * A path served for one verb is not served for another. Conflating them would let the dry run
     * report a match the router would have answered 405 to.
     */
    @Test
    void doesNotMatchTheRightPathUnderTheWrongMethod() {
        assertThat(
                        OperationLocator.forRest(
                                List.of(rest("getOrder", HttpMethod.GET, "/orders/{orderId}")),
                                "POST",
                                path("/orders/42")))
                .isEmpty();
    }

    @Test
    void tellsMethodsApartWhenOnePathServesSeveral() {
        List<OperationDefinition> operations =
                List.of(rest("listOrders", HttpMethod.GET, "/orders"), rest("createOrder", HttpMethod.POST, "/orders"));

        assertThat(OperationLocator.forRest(operations, "POST", path("/orders")).orElseThrow().operation().operationId())
                .isEqualTo("createOrder");
    }

    @Test
    void theMethodIsComparedWithoutRegardToCase() {
        assertThat(OperationLocator.forRest(List.of(rest("listOrders", HttpMethod.GET, "/orders")), "get", path("/orders")))
                .isPresent();
    }

    @Test
    void answersNothingForAPathNoTemplateAccepts() {
        assertThat(
                        OperationLocator.forRest(
                                List.of(rest("getOrder", HttpMethod.GET, "/orders/{orderId}")),
                                "GET",
                                path("/invoices/42")))
                .isEmpty();
    }

    /** A request with no method stated cannot identify a REST operation, and must not guess one. */
    @Test
    void answersNothingWhenNoMethodWasGiven() {
        assertThat(
                        OperationLocator.forRest(
                                List.of(rest("getOrder", HttpMethod.GET, "/orders/{orderId}")), null, path("/orders/42")))
                .isEmpty();
    }

    // --- SOAP ---------------------------------------------------------------

    @Test
    void mapsABodyElementToTheOperationItIdentifies() {
        var match = OperationLocator.forSoap(service(true), element("EchoRequest"));

        assertThat(match)
                .isInstanceOfSatisfying(
                        OperationLocator.SoapMatch.Served.class,
                        served -> assertThat(served.operation().operationId()).isEqualTo("Echo"));
    }

    /**
     * Declared by the contract but absent from configuration is its own answer. Reported as
     * unknown it would read as a typo in the envelope, when the fix is a line of configuration.
     */
    @Test
    void separatesAnUnconfiguredOperationFromAnUnknownOne() {
        var match = OperationLocator.forSoap(service(false), element("EchoRequest"));

        assertThat(match)
                .isInstanceOfSatisfying(
                        OperationLocator.SoapMatch.NotConfigured.class,
                        notConfigured -> assertThat(notConfigured.operationName()).isEqualTo("Echo"));
    }

    @Test
    void reportsAnElementNoContractDeclaresAsUnknown() {
        assertThat(OperationLocator.forSoap(service(true), element("SomethingElse")))
                .isInstanceOf(OperationLocator.SoapMatch.Unknown.class);
    }

    /** A pasted envelope carries no endpoint, so the element alone has to find its service. */
    @Test
    void findsTheOwningServiceWhenSearchingAcrossAllOfThem() {
        var match =
                OperationLocator.forSoap(
                        List.of(otherService(), service(true)), element("EchoRequest"));

        assertThat(match)
                .isInstanceOfSatisfying(
                        OperationLocator.SoapMatch.Served.class,
                        served -> assertThat(served.service().serviceId()).isEqualTo("svc"));
    }

    @Test
    void searchingAcrossServicesStillDistinguishesUnconfiguredFromUnknown() {
        assertThat(OperationLocator.forSoap(List.of(otherService(), service(false)), element("EchoRequest")))
                .isInstanceOf(OperationLocator.SoapMatch.NotConfigured.class);

        assertThat(OperationLocator.forSoap(List.of(otherService(), service(true)), element("Nothing")))
                .isInstanceOf(OperationLocator.SoapMatch.Unknown.class);
    }

    @Test
    void noServicesAtAllIsUnknownRatherThanAFailure() {
        assertThat(OperationLocator.forSoap(List.of(), element("EchoRequest")))
                .isInstanceOf(OperationLocator.SoapMatch.Unknown.class);
    }

    // --- fixtures -----------------------------------------------------------

    private static PathContainer path(String value) {
        return PathContainer.parsePath(value);
    }

    private static QName element(String localName) {
        return new QName(NS, localName);
    }

    private static OperationDefinition rest(String operationId, HttpMethod method, String path) {
        return new OperationDefinition(
                "svc", operationId, method, path, 200, "application/json", List.of(), KeyStrategy.ALL);
    }

    /** @param configured whether the operation the contract declares is also served */
    private static SoapServiceDefinition service(boolean configured) {
        Map<String, SoapOperationDefinition> served =
                configured
                        ? Map.of(
                                "Echo",
                                new SoapOperationDefinition(
                                        "svc", "Echo", null, List.of(KeySpec.parse("xpath:/x:Echo/x:id")), KeyStrategy.ALL))
                        : Map.of();

        return new SoapServiceDefinition(
                "svc",
                "/soap/svc",
                "<definitions/>",
                NS,
                null,
                Map.of(element("EchoRequest"), "Echo"),
                served,
                Map.of(),
                Map.of(),
                null,
                null);
    }

    /** A service that maps nothing, to prove the search does not stop at the first one it sees. */
    private static SoapServiceDefinition otherService() {
        return new SoapServiceDefinition(
                "other", "/soap/other", "<definitions/>", NS, null, Map.of(), Map.of(), Map.of(), Map.of(), null, null);
    }
}
