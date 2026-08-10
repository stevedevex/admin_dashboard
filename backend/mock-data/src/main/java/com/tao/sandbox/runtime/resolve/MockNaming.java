package com.tao.sandbox.runtime.resolve;

import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.runtime.match.Normaliser;
import com.tao.sandbox.spec.ServedOperation;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockStem;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.Payloads;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import org.springframework.stereotype.Component;

/**
 * The one place a mock's filename is decided.
 *
 * <p>A mock is reachable only if the name it was saved under is the name resolution computes from
 * an incoming request. Both sides of that equality are derived here — the same normalisation, the
 * same declaration order, the same key strategy — because the failure when they disagree is
 * silent: a file that exists, lists in the tree, and no request ever resolves to.
 *
 * <p>That risk was already understood at the module's edge, which is why the dashboard asks the
 * server for names rather than building them. It was not held inside the server, where the naming
 * rules had grown three copies: this class, the control panel's {@code /mocks/name}, and the draft
 * offered for a recorded call. The strategy rule itself lives further down still, on {@link
 * com.tao.sandbox.config.SandboxProperties.KeyStrategy}, so that live extraction and naming cannot
 * answer it differently.
 *
 * <p>Only {@link #extensionFor} needs the contract; everything else is a function of the keys
 * and the operation's own declaration, and is static so that the naming rules can be exercised
 * against hand-built operations without a spec, a registry or a context behind them.
 *
 * <p>Sits in {@code runtime.resolve} because that is where the three things a name needs already
 * meet: the contract (what the operation declares), the match rules (how a value is normalised),
 * and the store (what a filename means).
 */
@Component
public class MockNaming {

    private final SpecRegistry registry;

    public MockNaming(SpecRegistry registry) {
        this.registry = registry;
    }

    /**
     * The filename stem for a set of already-extracted keys.
     *
     * <p>No keys is not a mistake: it names the fallback resolution tries when nothing more
     * specific matches, which is a mock an author legitimately wants to write.
     */
    public static String stemFor(String serviceId, String operationId, SequencedMap<String, String> keys) {
        return keys.isEmpty() ? MockRepository.DEFAULT_STEM : MockStem.of(keys);
    }

    /** Stem and extension together — the name a mock is actually saved under. */
    public String fileNameFor(String serviceId, String operationId, SequencedMap<String, String> keys) {
        return stemFor(serviceId, operationId, keys) + "." + extensionFor(serviceId, operationId);
    }

    /** SOAP is always XML; REST takes the media type its contract declares. */
    public String extensionFor(String serviceId, String operationId) {
        return registry
                .findRest(serviceId, operationId)
                .map(rest -> Payloads.extensionFor(rest.responseContentType()))
                .orElse("xml");
    }

    /**
     * Whether these keys identify a specific mock for this operation, or fall through to its
     * default. The same question live extraction asks, answered by the same rule.
     */
    public static boolean satisfies(ServedOperation operation, Map<String, String> keys) {
        return operation.strategy().satisfiedBy(keys.size(), operation.keys().size());
    }

    /**
     * Matches caller-supplied key values against what the operation declares, normalising each and
     * keeping declaration order.
     *
     * <p>Values arrive loosely named — {@code GET /__tao/services} reports a key's name, its raw
     * expression and its full declaration, and a caller holding any of them means the same field.
     * An aliased key has one spelling more: the name its schema uses, which is what somebody
     * writing a request by hand is most likely to reach for. Being strict would convert a
     * reasonable choice into an empty key set, which names the operation's default — a wrong
     * answer wearing the shape of a right one.
     */
    public static SequencedMap<String, String> resolveKeys(
            ServedOperation operation, Map<String, String> supplied) {

        SequencedMap<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> from = supplied == null ? Map.of() : supplied;

        for (KeySpec key : operation.keys()) {
            Optional<String> value = lookup(from, key).flatMap(Normaliser::normalise);
            if (value.isEmpty()) {
                continue;
            }

            resolved.put(key.name(), value.get());

            if (operation.strategy().takesFirstOnly()) {
                // Anything after the first present key is never read from a request, so it must
                // not be written into a name either.
                break;
            }
        }

        return resolved;
    }

    /**
     * The keys as they appear in the filename — lowercased, exactly as {@link MockStem#of}
     * writes them, so a caller can see what its values became.
     */
    public static SequencedMap<String, String> asWritten(SequencedMap<String, String> keys) {
        SequencedMap<String, String> written = new LinkedHashMap<>();
        keys.forEach((key, value) -> written.put(key, value.toLowerCase(Locale.ROOT)));
        return written;
    }

    private static Optional<String> lookup(Map<String, String> supplied, KeySpec key) {
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            if (key.matchesName(entry.getKey())) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }
}
