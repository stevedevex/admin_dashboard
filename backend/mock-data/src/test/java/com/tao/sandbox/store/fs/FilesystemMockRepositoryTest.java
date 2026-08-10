package com.tao.sandbox.store.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.config.SandboxProperties.StoreType;
import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockMeta;
import com.tao.sandbox.store.MockProvider;
import com.tao.sandbox.store.MockQuery;
import com.tao.sandbox.store.MockSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolution order, over a scenario tree built for the test rather than the sample library.
 *
 * <p>This is the module's central rule and the one it has already got wrong once: <em>the filename
 * is the outer dimension</em>. An exact key match anywhere in the inheritance chain beats a nearer
 * {@code _default}, because the better address wins wherever it was found. Walking scenario-first
 * instead lets the active scenario's fallback shadow an inherited exact match — which presents as
 * a mock that exists, is listed, and never answers.
 *
 * <p>Everything here uses invented service and operation names and needs no contract: the store
 * resolves addresses, and knows nothing about specs.
 */
class FilesystemMockRepositoryTest {

    private static final String SERVICE = "svc";
    private static final String OPERATION = "op";

    // --- resolution order ---------------------------------------------------

    @Test
    void anExactKeyMatchBeatsTheOperationsDefault(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "exact");
        mock(root, "base", "_default.json", "fallback");

        assertThat(bodyOf(load(root).resolve(query("base", "id", "1")))).isEqualTo("exact");
    }

    /**
     * The regression. {@code child} owns a default; {@code base} owns the exact match. The exact
     * match wins even though it is further away, because specificity of address outranks nearness
     * of scenario.
     */
    @Test
    void anInheritedExactMatchBeatsANearerDefault(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "inherited exact");
        scenario(root, "child", "base");
        mock(root, "child", "_default.json", "nearer default");

        assertThat(bodyOf(load(root).resolve(query("child", "id", "1")))).isEqualTo("inherited exact");
    }

    /** At equal specificity, nearness decides: a scenario overrides the same filename in its parent. */
    @Test
    void aNearerScenarioOverridesTheSameFileNameInItsAncestor(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "from base");
        scenario(root, "child", "base");
        mock(root, "child", "id=1.json", "from child");

        MockProvider.Resolved resolved = load(root).resolve(query("child", "id", "1")).orElseThrow();

        assertThat(resolved.document().body()).isEqualTo("from child");
        assertThat(resolved.inherited()).isFalse();
    }

    @Test
    void reportsTheScenarioAMatchWasActuallyFoundIn(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "from base");
        scenario(root, "child", "base");

        MockProvider.Resolved resolved = load(root).resolve(query("child", "id", "1")).orElseThrow();

        assertThat(resolved.scenarioId()).isEqualTo("base");
        assertThat(resolved.inherited()).isTrue();
    }

    /** Inheritance is followed the whole way up, not one level. */
    @Test
    void walksTheWholeInheritanceChain(@TempDir Path root) throws IOException {
        scenario(root, "grandparent", null);
        mock(root, "grandparent", "id=1.json", "from grandparent");
        scenario(root, "parent", "grandparent");
        scenario(root, "child", "parent");

        assertThat(bodyOf(load(root).resolve(query("child", "id", "1")))).isEqualTo("from grandparent");
    }

    @Test
    void fallsBackToTheDefaultWhenNoKeyMatches(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "_default.json", "fallback");

        assertThat(bodyOf(load(root).resolve(query("base", "id", "9")))).isEqualTo("fallback");
    }

    @Test
    void answersNothingWhenNeitherAKeyMatchNorADefaultExists(@TempDir Path root) throws IOException {
        scenario(root, "base", null);

        assertThat(load(root).resolve(query("base", "id", "1"))).isEmpty();
    }

    /**
     * The candidate list is what a miss diagnostic prints, so it has to be the order actually
     * tried — filename outermost — or the diagnostic sends its reader to the wrong file.
     */
    @Test
    void candidatesAreListedInTheOrderResolutionTriesThem(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        scenario(root, "child", "base");

        assertThat(load(root).candidates(query("child", "id", "1")))
                .containsExactly(
                        "scenarios/child/svc/op/id=1",
                        "scenarios/base/svc/op/id=1",
                        "scenarios/child/svc/op/_default",
                        "scenarios/base/svc/op/_default");
    }

    /**
     * A payload's format is a property of its contract, not of the request, so the extension is
     * not part of the address. Any sibling with the right stem is the mock.
     */
    @Test
    void theExtensionIsNotPartOfTheAddress(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.txt", "stored as text");

        assertThat(bodyOf(load(root).resolve(query("base", "id", "1")))).isEqualTo("stored as text");
    }

    // --- browsing -----------------------------------------------------------

    @Test
    void listMarksAnInheritedMockAndNamesWhereItCameFrom(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "from base");
        scenario(root, "child", "base");
        mock(root, "child", "id=2.json", "from child");

        Map<String, MockSummary> byFile = new LinkedHashMap<>();
        load(root).list("child", null).forEach(mock -> byFile.put(mock.id().fileName(), mock));

        assertThat(byFile.get("id=2.json").inherited()).isFalse();
        assertThat(byFile.get("id=1.json").inherited()).isTrue();
        assertThat(byFile.get("id=1.json").inheritedFrom()).isEqualTo("base");
    }

    /** An overridden slot is listed once — as the copy that would actually answer. */
    @Test
    void listShowsAnOverriddenSlotOnlyOnce(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "from base");
        scenario(root, "child", "base");
        mock(root, "child", "id=1.json", "from child");

        assertThat(load(root).list("child", null))
                .singleElement()
                .satisfies(mock -> assertThat(mock.inherited()).isFalse());
    }

    @Test
    void listCanBeNarrowedToOneService(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", SERVICE, OPERATION, "id=1.json", "one");
        mock(root, "base", "other", OPERATION, "id=1.json", "two");

        assertThat(load(root).list("base", SERVICE))
                .singleElement()
                .satisfies(mock -> assertThat(mock.id().serviceId()).isEqualTo(SERVICE));
    }

    // --- writing ------------------------------------------------------------

    @Test
    void aSavedMockResolvesWithoutAReload(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        FilesystemMockRepository repository = load(root);

        repository.save(new MockId("base", SERVICE, OPERATION, "id=1.json"), MockDocument.of("written"));

        assertThat(bodyOf(repository.resolve(query("base", "id", "1")))).isEqualTo("written");
    }

    @Test
    void savingWritesTheSidecarsBesideThePayload(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        FilesystemMockRepository repository = load(root);

        repository.save(
                new MockId("base", SERVICE, OPERATION, "id=1.json"),
                new MockDocument(
                        "written",
                        null,
                        new MockMeta(503, "application/problem+json", Map.of("X-Trace", "abc"), null),
                        "{\"asked\":\"for it\"}"));

        Path directory = root.resolve("scenarios/base/svc/op");
        assertThat(directory.resolve("id=1.meta.yaml")).exists();
        assertThat(directory.resolve("id=1.request.json")).exists();
        assertThat(repository.get(new MockId("base", SERVICE, OPERATION, "id=1.json")).orElseThrow().meta().status())
                .isEqualTo(503);
    }

    /** A sidecar left behind would re-apply to whatever is saved into the slot next. */
    @Test
    void deletingRemovesTheSidecarsTogetherWithThePayload(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        FilesystemMockRepository repository = load(root);
        MockId id = new MockId("base", SERVICE, OPERATION, "id=1.json");

        repository.save(
                id, new MockDocument("written", null, new MockMeta(503, null, Map.of(), null), null));
        repository.delete(id);

        Path directory = root.resolve("scenarios/base/svc/op");
        assertThat(directory.resolve("id=1.json")).doesNotExist();
        assertThat(directory.resolve("id=1.meta.yaml")).doesNotExist();
        assertThat(repository.resolve(query("base", "id", "1"))).isEmpty();
    }

    /**
     * Writing into a scenario that does not exist would create its directory as a side effect, and
     * a scenario with no descriptor inherits from nothing — so the mock would resolve only for a
     * caller naming that scenario exactly, which is never what was meant.
     */
    @Test
    void refusesToSaveIntoAScenarioThatDoesNotExist(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        FilesystemMockRepository repository = load(root);

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                repository.save(
                                        new MockId("absent", SERVICE, OPERATION, "id=1.json"),
                                        MockDocument.of("written")))
                .withMessageContaining("absent");
    }

    // --- scenarios ----------------------------------------------------------

    /** Orphaning a child would silently shrink what it serves, with nothing to say so. */
    @Test
    void refusesToDeleteAScenarioThatIsExtended(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        scenario(root, "child", "base");
        FilesystemMockRepository repository = load(root);

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.deleteScenario("base"))
                .withMessageContaining("child");
    }

    @Test
    void refusesToCreateAScenarioExtendingOneThatDoesNotExist(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        FilesystemMockRepository repository = load(root);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.createScenario("child", null, null, "absent"))
                .withMessageContaining("absent");
    }

    /** The id becomes a directory name, so anything that is a path rather than a segment is refused. */
    @Test
    void refusesAScenarioIdThatIsAPath(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        FilesystemMockRepository repository = load(root);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.createScenario("../escape", null, null, null));
    }

    @Test
    void aCreatedScenarioIsImmediatelyUsable(@TempDir Path root) throws IOException {
        scenario(root, "base", null);
        mock(root, "base", "id=1.json", "from base");
        FilesystemMockRepository repository = load(root);

        repository.createScenario("child", "Child", "a delta on base", "base");

        assertThat(bodyOf(repository.resolve(query("child", "id", "1")))).isEqualTo("from base");
    }

    /**
     * A cycle leaves chain-walking terminating but silently truncating, so it is refused at load
     * time where the message can name the scenarios involved.
     */
    @Test
    void detectsAnInheritanceCycleAtLoad(@TempDir Path root) throws IOException {
        scenario(root, "a", "b");
        scenario(root, "b", "a");

        assertThatIllegalStateException()
                .isThrownBy(() -> load(root))
                .withMessageContaining("cycle");
    }

    /** No scenarios directory at all is a warning, not a failure: the sandbox simply serves nothing. */
    @Test
    void anEmptyStoreLoadsAndAnswersNothing(@TempDir Path root) {
        FilesystemMockRepository repository = load(root);

        assertThat(repository.scenarios()).isEmpty();
        assertThat(repository.resolve(query("base", "id", "1"))).isEmpty();
    }

    // --- fixtures -----------------------------------------------------------

    private static FilesystemMockRepository load(Path root) {
        FilesystemMockRepository repository =
                new FilesystemMockRepository(
                        new SandboxProperties(
                                StoreType.FILESYSTEM,
                                new SandboxProperties.Filesystem(root.toString()),
                                null,
                                null,
                                null,
                                null));
        repository.reload();
        return repository;
    }

    private static void scenario(Path root, String id, String parent) throws IOException {
        Path directory = root.resolve("scenarios").resolve(id);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("scenario.yaml"),
                "name: %s\n".formatted(id) + (parent == null ? "" : "extends: %s\n".formatted(parent)),
                StandardCharsets.UTF_8);
    }

    private static void mock(Path root, String scenarioId, String fileName, String body) throws IOException {
        mock(root, scenarioId, SERVICE, OPERATION, fileName, body);
    }

    private static void mock(
            Path root, String scenarioId, String serviceId, String operationId, String fileName, String body)
            throws IOException {

        Path directory = root.resolve("scenarios").resolve(scenarioId).resolve(serviceId).resolve(operationId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), body, StandardCharsets.UTF_8);
    }

    private static MockQuery query(String scenarioId, String key, String value) {
        SequencedMap<String, String> keys = new LinkedHashMap<>();
        keys.put(key, value);
        return new MockQuery(scenarioId, SERVICE, OPERATION, keys);
    }

    private static String bodyOf(Optional<MockProvider.Resolved> resolved) {
        return resolved.orElseThrow().document().body();
    }
}
