package com.tao.sandbox.validate;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.spec.ServedOperation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether a stored mock could ever answer anything.
 *
 * <p>This is the check that makes the other two features safe to adopt. Aliasing a key renames
 * every file already saved under the old name, and an operation's strategy can be changed in
 * configuration long after its mocks were written — both silently, both leaving files that list,
 * validate, and never win. Everything here is over hand-built operations, so the rule is stated
 * rather than inferred from whichever sample library happens to be configured.
 */
class MockReachabilityTest {

    @Test
    void aNameUsingEveryDeclaredKeyIsReachableUnderAll() {
        assertThat(MockReachability.of(operation(KeyStrategy.ALL, "path:brid", "path:bc"), "brid=1001&bc=ch100.xml"))
                .satisfies(verdict -> assertThat(verdict.reachable()).isTrue());
    }

    /** Zero keys names the fallback, which every strategy reaches. */
    @Test
    void theDefaultIsAlwaysReachable() {
        assertThat(MockReachability.of(operation(KeyStrategy.ALL, "path:brid", "path:bc"), "_default.xml").reachable())
                .isTrue();
        assertThat(MockReachability.of(operation(KeyStrategy.FIRST_PRESENT, "path:brid"), "_default.json").reachable())
                .isTrue();
    }

    /**
     * The trap this exists for: a subset under a strategy that requires all. No request ever
     * produces that name, so the file is answered over by the operation's default forever.
     */
    @Test
    void aSubsetIsUnreachableUnderAll() {
        MockReachability.Verdict verdict =
                MockReachability.of(operation(KeyStrategy.ALL, "path:brid", "path:bc"), "brid=1001.xml");

        assertThat(verdict.reachable()).isFalse();
        assertThat(verdict.reason()).contains("all of").contains("brid", "bc");
    }

    @Test
    void moreThanOneKeyIsUnreachableUnderFirstPresent() {
        MockReachability.Verdict verdict =
                MockReachability.of(
                        operation(KeyStrategy.FIRST_PRESENT, "query:brid", "query:bc"), "brid=1001&bc=ch100.xml");

        assertThat(verdict.reachable()).isFalse();
        assertThat(verdict.reason()).contains("first key present");
    }

    @Test
    void oneKeyIsReachableUnderFirstPresent() {
        assertThat(
                        MockReachability.of(
                                        operation(KeyStrategy.FIRST_PRESENT, "query:brid", "query:bc"),
                                        "bc=ch100.xml")
                                .reachable())
                .isTrue();
    }

    /**
     * What aliasing does to a library that already has files: the old name mentions a key the
     * operation no longer declares under that spelling.
     */
    @Test
    void aNameLeftBehindByAnAliasIsReported() {
        ServedOperation aliased = operation(KeyStrategy.ALL, "xpath:/b:Request/b:BusinessRelationId as brid");

        assertThat(MockReachability.of(aliased, "brid=1001.xml").reachable()).isTrue();

        MockReachability.Verdict stale = MockReachability.of(aliased, "businessrelationid=1001.xml");
        assertThat(stale.reachable()).isFalse();
        assertThat(stale.reason()).contains("not declared").contains("businessrelationid");
    }

    /** Under best-match a subset is the design, so none of them is unreachable. */
    @Test
    void everySubsetIsReachableUnderBestMatch() {
        ServedOperation operation =
                operation(KeyStrategy.BEST_MATCH, "query:id", "query:name", "query:category", "query:price");

        assertThat(MockReachability.of(operation, "name=laptop&category=electronics.json").reachable()).isTrue();
        assertThat(MockReachability.of(operation, "id=1001.json").reachable()).isTrue();
        assertThat(MockReachability.of(operation, "_default.json").reachable()).isTrue();
    }

    /** An undeclared key is still unreachable, whatever the strategy: nothing ever reads it. */
    @Test
    void anUndeclaredKeyIsUnreachableEvenUnderBestMatch() {
        MockReachability.Verdict verdict =
                MockReachability.of(operation(KeyStrategy.BEST_MATCH, "query:name"), "colour=silver.json");

        assertThat(verdict.reachable()).isFalse();
        assertThat(verdict.reason()).contains("colour");
    }

    @Test
    void aNameThatIsNotKeyValueShapedIsReported() {
        MockReachability.Verdict verdict =
                MockReachability.of(operation(KeyStrategy.ALL, "path:brid"), "whatever-i-felt-like.xml");

        assertThat(verdict.reachable()).isFalse();
        assertThat(verdict.reason()).contains("/__tao/mocks/name");
    }

    /** The extension is not part of the address, so it must not affect the verdict. */
    @Test
    void theExtensionIsIgnored() {
        ServedOperation operation = operation(KeyStrategy.ALL, "path:brid");

        assertThat(MockReachability.of(operation, "brid=1001.xml").reachable()).isTrue();
        assertThat(MockReachability.of(operation, "brid=1001.json").reachable()).isTrue();
        assertThat(MockReachability.of(operation, "brid=1001").reachable()).isTrue();
    }

    @Test
    void recognisesTheDefaultWhateverItsExtension() {
        assertThat(MockReachability.namesTheDefault("_default.json")).isTrue();
        assertThat(MockReachability.namesTheDefault("_default")).isTrue();
        assertThat(MockReachability.namesTheDefault("brid=1001.json")).isFalse();
    }

    private static ServedOperation operation(KeyStrategy strategy, String... declarations) {
        List<KeySpec> keys = List.of(declarations).stream().map(KeySpec::parse).toList();

        return new ServedOperation() {
            @Override
            public String serviceId() {
                return "svc";
            }

            @Override
            public String operationId() {
                return "GetRelation";
            }

            @Override
            public List<KeySpec> keys() {
                return keys;
            }

            @Override
            public KeyStrategy strategy() {
                return strategy;
            }
        };
    }
}
