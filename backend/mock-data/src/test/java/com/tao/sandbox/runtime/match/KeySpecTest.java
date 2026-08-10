package com.tao.sandbox.runtime.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a declared key is read out of configuration, and what it is then called.
 *
 * <p>The derived name is not cosmetic: it becomes the {@code key=value} in every mock filename and
 * every trace line. A change to derivation renames files that already exist on disk, so the rule
 * is fixed here rather than left to be rediscovered from a resolution miss.
 */
class KeySpecTest {

    @Test
    void parsesEachSourceWithItsExpression() {
        assertThat(KeySpec.parse("path:orderId").source()).isEqualTo(KeySpec.Source.PATH);
        assertThat(KeySpec.parse("query:limit").source()).isEqualTo(KeySpec.Source.QUERY);
        assertThat(KeySpec.parse("header:X-Tenant").source()).isEqualTo(KeySpec.Source.HEADER);
        assertThat(KeySpec.parse("body:$.accountId").source()).isEqualTo(KeySpec.Source.BODY);
        assertThat(KeySpec.parse("xpath:/a:Envelope/a:Id").source()).isEqualTo(KeySpec.Source.XPATH);
    }

    @Test
    void acceptsTheSourceInAnyCaseAndIgnoresSurroundingSpace() {
        KeySpec key = KeySpec.parse("  PATH : orderId  ");

        assertThat(key.source()).isEqualTo(KeySpec.Source.PATH);
        assertThat(key.expression()).isEqualTo("orderId");
    }

    /** Path, query and header expressions are already the field's name. */
    @Test
    void namesAFlatSourceAfterItsExpression() {
        assertThat(KeySpec.parse("path:orderId").name()).isEqualTo("orderId");
        assertThat(KeySpec.parse("query:limit").name()).isEqualTo("limit");
    }

    /**
     * A long expression still has to produce a short filename. The leaf is what identifies the
     * field to a reader; the path to it is configuration's business, not the file listing's.
     */
    @Test
    void namesAnExpressionAfterItsLeaf() {
        assertThat(KeySpec.parse("body:$.customer.accountId").name()).isEqualTo("accountId");
        assertThat(KeySpec.parse("xpath:/soapenv:Envelope/soapenv:Body/x:Request/x:orderId").name())
                .isEqualTo("orderId");
    }

    /** An XPath leaf may be an attribute or carry a prefix; neither belongs in a filename. */
    @Test
    void stripsAttributeMarkersAndNamespacePrefixesFromTheName() {
        assertThat(KeySpec.parse("xpath:/x:Request/@x:id").name()).isEqualTo("id");
        assertThat(KeySpec.parse("xpath:/x:Request/x:orderId").name()).isEqualTo("orderId");
    }

    /**
     * A malformed declaration must fail at startup, where the message can name the service. Left
     * to the first request that needs it, the same mistake presents as a mock that never matches.
     */
    @Test
    void refusesADeclarationWithNoSource() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KeySpec.parse("orderId"))
                .withMessageContaining("source:expression");
    }

    @Test
    void refusesAnEmptyExpression() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KeySpec.parse("path:"))
                .withMessageContaining("empty");
    }

    @Test
    void refusesAnUnknownSourceAndNamesTheOnesItAccepts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KeySpec.parse("cookie:session"))
                .withMessageContaining("PATH");
    }

    // --- aliases ------------------------------------------------------------

    /**
     * A schema that spells things out produces names long enough to hurt — in a directory listing,
     * and against the total path length of a Windows checkout of the mock store.
     */
    @Test
    void anAliasReplacesTheNameWithoutTouchingTheExpression() {
        KeySpec key =
                KeySpec.parse("xpath:/soapenv:Envelope/soapenv:Body/b:Request/b:BusinessRelationId as brid");

        assertThat(key.name()).isEqualTo("brid");
        assertThat(key.expression())
                .isEqualTo("/soapenv:Envelope/soapenv:Body/b:Request/b:BusinessRelationId");
        assertThat(key.source()).isEqualTo(KeySpec.Source.XPATH);
    }

    @Test
    void anAliasWorksOnEverySource() {
        assertThat(KeySpec.parse("path:businessRelationId as brid").name()).isEqualTo("brid");
        assertThat(KeySpec.parse("query:bookingCentre as bc").name()).isEqualTo("bc");
        assertThat(KeySpec.parse("body:$.customer.businessRelationId as brid").name()).isEqualTo("brid");
    }

    /** The name a key would have had is still reachable, because callers may still use it. */
    @Test
    void theSchemasOwnNameSurvivesAnAlias() {
        KeySpec key = KeySpec.parse("xpath:/b:Request/b:BusinessRelationId as brid");

        assertThat(key.name()).isEqualTo("brid");
        assertThat(key.derivedName()).isEqualTo("BusinessRelationId");
    }

    @Test
    void withoutAnAliasTheNameAndTheDerivedNameAgree() {
        KeySpec key = KeySpec.parse("path:orderId");

        assertThat(key.name()).isEqualTo(key.derivedName()).isEqualTo("orderId");
    }

    /** Split on the last one, so an expression that happens to contain the word keeps it. */
    @Test
    void onlyTheFinalAsIntroducesTheAlias() {
        KeySpec key = KeySpec.parse("query:sort as name as sortkey");

        assertThat(key.name()).isEqualTo("sortkey");
        assertThat(key.expression()).isEqualTo("sort as name");
    }

    @Test
    void anExpressionEndingInTheWordAsIsNotAnAlias() {
        assertThat(KeySpec.parse("query:as").name()).isEqualTo("as");
        assertThat(KeySpec.parse("query:sortas").name()).isEqualTo("sortas");
    }

    /**
     * Refused rather than folded back into the expression. Guessing which was meant would make one
     * of the two readings a silent mistake, and the silent one is a key that never matches.
     */
    @Test
    void refusesAnAliasThatCouldNotBePartOfAFileName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KeySpec.parse("path:orderId as order id"))
                .withMessageContaining("not a usable key name");

        assertThatIllegalArgumentException().isThrownBy(() -> KeySpec.parse("path:orderId as a=b"));
        assertThatIllegalArgumentException().isThrownBy(() -> KeySpec.parse("path:orderId as a&b"));
        assertThatIllegalArgumentException().isThrownBy(() -> KeySpec.parse("path:orderId as "));
    }

    @Test
    void refusesAnEmptyDeclaration() {
        assertThatIllegalArgumentException().isThrownBy(() -> KeySpec.parse("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> KeySpec.parse(null));
    }

    // --- recognising a key by any name that means it -------------------------

    @Test
    void aKeyAnswersToItsNameExpressionAndWholeDeclaration() {
        KeySpec key = KeySpec.parse("body:$.customer.accountId");

        assertThat(key.matchesName("accountId")).isTrue();
        assertThat(key.matchesName("$.customer.accountId")).isTrue();
        assertThat(key.matchesName("BODY:$.customer.accountId")).isTrue();
        assertThat(key.matchesName("ACCOUNTID")).isTrue();
    }

    /**
     * An aliased key answers to both, which is what stops the dry run reporting the field it read
     * as one it discarded — the field is spelled one way and the key is called another.
     */
    @Test
    void anAliasedKeyAnswersToBothItsNames() {
        KeySpec key = KeySpec.parse("query:productPriceInMinorUnits as price");

        assertThat(key.matchesName("price")).isTrue();
        assertThat(key.matchesName("productPriceInMinorUnits")).isTrue();
    }

    @Test
    void aKeyDoesNotAnswerToSomethingElse() {
        KeySpec key = KeySpec.parse("query:productPriceInMinorUnits as price");

        assertThat(key.matchesName("correlationId")).isFalse();
        assertThat(key.matchesName("pric")).isFalse();
        assertThat(key.matchesName(null)).isFalse();
    }

    // --- parsing a whole operation's keys -----------------------------------

    @Test
    void parsesEveryDeclarationAndReportsNothingWhenAllAreGood() {
        List<String> problems = new ArrayList<>();

        List<KeySpec> keys =
                KeySpec.parseAll(List.of("path:orderId", "query:region as rg"), "orders/getOrder", problems);

        assertThat(problems).isEmpty();
        assertThat(keys).extracting(KeySpec::name).containsExactly("orderId", "rg");
    }

    /** Startup reports every fault at once, so one unusable declaration must not hide the next. */
    @Test
    void reportsEveryBadDeclarationRatherThanStoppingAtTheFirst() {
        List<String> problems = new ArrayList<>();

        List<KeySpec> keys =
                KeySpec.parseAll(List.of("nonsense", "path:", "query:limit"), "orders/getOrder", problems);

        assertThat(problems).hasSize(2).allSatisfy(problem -> assertThat(problem).startsWith("orders/getOrder:"));
        assertThat(keys).extracting(KeySpec::name).containsExactly("limit");
    }

    /**
     * Two keys reaching a filename under one name is one key: the mock written for the second would
     * land on the first. Caught where the message can name the operation.
     */
    @Test
    void refusesTwoKeysThatWouldShareAName() {
        List<String> problems = new ArrayList<>();

        KeySpec.parseAll(
                List.of("xpath:/a/b:BusinessRelationId as brid", "xpath:/c/d:BookingCentre as brid"),
                "billing/GetRelation",
                problems);

        assertThat(problems).singleElement().asString().contains("both called 'brid'");
    }

    /** Filenames are lowercased, so two names differing only in case are the same name. */
    @Test
    void namesThatDifferOnlyInCaseStillCollide() {
        List<String> problems = new ArrayList<>();

        KeySpec.parseAll(List.of("path:a as brid", "path:b as BRID"), "billing/GetRelation", problems);

        assertThat(problems).hasSize(1);
    }

    /** Two different expressions whose leaves happen to agree collide just as surely. */
    @Test
    void derivedNamesCollideToo() {
        List<String> problems = new ArrayList<>();

        KeySpec.parseAll(
                List.of("xpath:/a/b:Id", "xpath:/c/d:Id"), "billing/GetRelation", problems);

        assertThat(problems).singleElement().asString().contains("both called 'Id'");
    }
}
