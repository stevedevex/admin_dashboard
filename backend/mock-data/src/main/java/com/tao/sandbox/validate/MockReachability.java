package com.tao.sandbox.validate;

import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.MockStem;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Whether a stored mock could ever answer anything.
 *
 * <p>Validation asks whether a payload is the right <em>shape</em>. This asks something the schema
 * cannot: whether the file is at an address any request produces. A mock can be perfectly valid,
 * listed in the tree, marked clean — and unreachable, because its name mentions a key the
 * operation does not declare, or names a subset under a strategy that demands all of them. Nothing
 * about serving reveals it. The mock simply never wins, and the default answers instead.
 *
 * <p>That failure is the one this whole area keeps producing, and it became more likely with two
 * recent changes: aliasing a key <em>renames every file already saved under the old name</em>, and
 * an operation's strategy can be changed in configuration long after its mocks were written. Both
 * are silent. So they are reported.
 *
 * <p>Not an error and never a refusal. A file that cannot be reached today is a file whose
 * configuration may be about to change, and deleting or rejecting it would throw away the author's
 * work over a disagreement about which of the two is wrong.
 */
@Component
public class MockReachability {

    /** @param reason null when the mock is reachable */
    public record Verdict(boolean reachable, String reason) {

        static final Verdict REACHABLE = new Verdict(true, null);

        static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    private final SpecRegistry registry;

    public MockReachability(SpecRegistry registry) {
        this.registry = registry;
    }

    public Verdict of(MockId id) {
        Optional<ServedOperation> served = registry.findOperation(id.serviceId(), id.operationId());

        return served.isPresent()
                ? of(served.get(), id.fileName())
                : Verdict.no(
                        "%s/%s is not served — configuration or the spec changed since this mock was written."
                                .formatted(id.serviceId(), id.operationId()));
    }

    /**
     * The rule itself, over an operation and a filename.
     *
     * <p>Static and registry-free so it can be exercised against hand-built operations: what makes
     * a name reachable is a property of the keys and the strategy, and nothing about looking those
     * up belongs in stating it.
     */
    public static Verdict of(ServedOperation operation, String fileName) {
        String stem = stripExtension(fileName);

        Optional<SequencedMap<String, String>> named = MockStem.parse(stem);
        if (named.isEmpty()) {
            return Verdict.no(
                    "'%s' is not a key=value name, so no request produces it. Ask POST /__tao/mocks/name for the name this operation's keys make."
                            .formatted(stem));
        }

        Set<String> declared = new LinkedHashSet<>();
        operation.keys().forEach(key -> declared.add(key.name().toLowerCase(Locale.ROOT)));

        List<String> unknown =
                named.get().keySet().stream().filter(key -> !declared.contains(key)).toList();

        if (!unknown.isEmpty()) {
            return Verdict.no(
                    "%s is not declared by %s, which reads %s. A name using it can never be produced from a request."
                            .formatted(unknown, operation.operationId(), declared));
        }

        // Zero keys names the operation's fallback, which every strategy reaches.
        int used = named.get().size();
        if (used == 0) {
            return Verdict.REACHABLE;
        }

        return switch (operation.strategy()) {
            case ALL ->
                    used == operation.keys().size()
                            ? Verdict.REACHABLE
                            : Verdict.no(
                                    "%s resolves on all of %s, so a name from %d of them is never computed. Name every key, or none for the operation's default."
                                            .formatted(
                                                    operation.operationId(),
                                                    operation.keys().stream().map(KeySpec::name).toList(),
                                                    used));

            case FIRST_PRESENT ->
                    used == 1
                            ? Verdict.REACHABLE
                            : Verdict.no(
                                    "%s takes the first key present and nothing after it, so a name is only ever built from one key — this one names %d."
                                            .formatted(operation.operationId(), used));

            // Any subset of the declared keys is the whole point: this file says which of them it
            // matches on, and a request carrying at least those reaches it.
            case BEST_MATCH -> Verdict.REACHABLE;
        };
    }

    /** The extension is not part of the address; any sibling with the right stem is the mock. */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Kept beside the rule it belongs to, so a caller need not know the fallback's spelling. */
    public static boolean namesTheDefault(String fileName) {
        return stripExtension(fileName).equals(MockRepository.DEFAULT_STEM);
    }
}
