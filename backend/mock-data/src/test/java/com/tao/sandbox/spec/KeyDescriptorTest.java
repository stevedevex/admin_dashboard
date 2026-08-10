package com.tao.sandbox.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.runtime.match.KeySpec;
import org.junit.jupiter.api.Test;

/**
 * What a reader is told about a key.
 *
 * <p>The name alone is enough while it is the field's own. An alias is the case it is not: {@code
 * brid} is a short name configuration chose, and nothing about it says which field it reads. So the
 * field's own name travels beside it — and only then, because repeating a name that already
 * explains itself is noise on every row of the services page.
 */
class KeyDescriptorTest {

    @Test
    void reportsNoAliasWhenTheNameIsTheFieldsOwn() {
        KeyDescriptor described = KeyDescriptor.of(KeySpec.parse("path:orderId"));

        assertThat(described.name()).isEqualTo("orderId");
        assertThat(described.aliasOf()).isNull();
    }

    @Test
    void reportsTheFieldAnAliasStandsFor() {
        KeyDescriptor described =
                KeyDescriptor.of(
                        KeySpec.parse("xpath:/soapenv:Envelope/soapenv:Body/b:Request/b:BusinessRelationId as brid"));

        assertThat(described.name()).isEqualTo("brid");
        assertThat(described.aliasOf()).isEqualTo("BusinessRelationId");
    }

    /**
     * An alias that only re-spells the field is not an alias worth reporting: a name and an
     * `aliasOf` differing by case would read as two fields where there is one.
     */
    @Test
    void treatsAnAliasSpelledLikeTheFieldAsNoAliasAtAll() {
        assertThat(KeyDescriptor.of(KeySpec.parse("query:limit as LIMIT")).aliasOf()).isNull();
    }

    @Test
    void carriesTheSourceAndExpressionThroughUnchanged() {
        KeyDescriptor described = KeyDescriptor.of(KeySpec.parse("body:$.customer.accountId as acct"));

        assertThat(described.source()).isEqualTo("BODY");
        assertThat(described.expression()).isEqualTo("$.customer.accountId");
        assertThat(described.aliasOf()).isEqualTo("accountId");
    }
}
