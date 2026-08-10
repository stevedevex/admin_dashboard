package com.tao.sandbox.runtime.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
}
