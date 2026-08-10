package com.tao.sandbox.store.fs;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.config.SandboxProperties.StoreType;
import com.tao.sandbox.store.MockProvider;
import com.tao.sandbox.store.MockQuery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Subset matching: the file says which keys it matches on, and the most specific eligible file wins.
 *
 * <p>The case this exists for is the ordinary one. A call carries id, name, category and price;
 * the mock that should answer it was written for <em>name and category</em>, and the other two are
 * whatever they happen to be that day. Exact matching cannot express it — the filename would have
 * to pin all four — and first-present cannot either, since it stops at one key.
 *
 * <p>Built over invented services and hand-written files, so nothing here depends on a contract.
 */
class BestMatchResolutionTest {

    private static final String SERVICE = "catalogue";
    private static final String OPERATION = "getProduct";

    /** The payload from the brief: {@code id, name, category, price}, four keys, any subset named. */
    @Test
    void aFileNamingTwoKeysAnswersACallCarryingFour(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop&category=electronics.json", "the two-key mock");
        mock(root, "base", "_default.json", "fallback");

        assertThat(body(load(root).resolve(fullPayload("base")))).isEqualTo("the two-key mock");
    }

    /** Most keys named wins: the request satisfies both, and the more specific one answers. */
    @Test
    void theFileNamingMostKeysWins(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "one key");
        mock(root, "base", "name=laptop&category=electronics.json", "two keys");

        assertThat(body(load(root).resolve(fullPayload("base")))).isEqualTo("two keys");
    }

    /** Scenario B from the brief: either condition matches, and each has its own response. */
    @Test
    void twoSingleKeyFilesGiveEitherOrWithDifferentAnswers(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "matched on name");
        mock(root, "base", "category=electronics.json", "matched on category");
        FilesystemMockRepository repository = load(root);

        assertThat(body(repository.resolve(query("base", "name", "Laptop")))).isEqualTo("matched on name");
        assertThat(body(repository.resolve(query("base", "category", "Electronics"))))
                .isEqualTo("matched on category");
    }

    /** Scenario C: four keys declared, one supplied, the rest absent. */
    @Test
    void aCallCarryingOneKeyReachesTheFileNamedForIt(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "id=1001.json", "by id");
        mock(root, "base", "_default.json", "fallback");

        assertThat(body(load(root).resolve(query("base", "id", "1001")))).isEqualTo("by id");
    }

    /** A file naming a key the request did not carry is not eligible, however specific it looks. */
    @Test
    void aFileIsIgnoredWhenTheRequestDoesNotCarryItsKeys(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop&colour=silver.json", "needs colour");
        mock(root, "base", "name=laptop.json", "just the name");

        assertThat(body(load(root).resolve(query("base", "name", "Laptop")))).isEqualTo("just the name");
    }

    /** Right key, wrong value: eligibility is on the value, not merely on the key being present. */
    @Test
    void aFileIsIgnoredWhenAValueDiffers(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "for laptops");
        mock(root, "base", "_default.json", "fallback");

        assertThat(body(load(root).resolve(query("base", "name", "Monitor")))).isEqualTo("fallback");
    }

    /** Naming no keys is the least specific eligible file, not a special case. */
    @Test
    void theDefaultAnswersWhenNothingMoreSpecificMatches(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "for laptops");
        mock(root, "base", "_default.json", "fallback");

        assertThat(body(load(root).resolve(query("base", "category", "Electronics")))).isEqualTo("fallback");
    }

    /**
     * Values are normalised the same way on both sides — the request's are already normalised when
     * they arrive, and the stored name is lowercased.
     */
    @Test
    void matchingIsCaseInsensitiveBecauseNamesAreLowercased(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "category=electronics.json", "matched");

        assertThat(body(load(root).resolve(query("base", "category", "ELECTRONICS")))).isEqualTo("matched");
    }

    /** Ties go to the file whose keys come first in declaration order — never to directory order. */
    @Test
    void anEquallySpecificTieIsBrokenByDeclarationOrder(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "by name");
        mock(root, "base", "category=electronics.json", "by category");

        // 'name' is declared before 'category', so it wins when both are eligible.
        assertThat(body(load(root).resolve(fullPayload("base")))).isEqualTo("by name");
    }

    /** Specificity stays the outer dimension, exactly as it is for exact matching. */
    @Test
    void anInheritedMoreSpecificFileBeatsANearerLessSpecificOne(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop&category=electronics.json", "inherited, two keys");
        scenarioExtending(root, "child", "base");
        mock(root, "child", "name=laptop.json", "nearer, one key");

        assertThat(body(load(root).resolve(fullPayload("child")))).isEqualTo("inherited, two keys");
    }

    /** At equal specificity, nearness decides — the same rule exact matching applies. */
    @Test
    void anOverridingCopyInTheNearerScenarioWins(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "from base");
        scenarioExtending(root, "child", "base");
        mock(root, "child", "name=laptop.json", "from child");

        assertThat(body(load(root).resolve(fullPayload("child")))).isEqualTo("from child");
    }

    /**
     * The miss diagnostic has to list what was tried in the order it was tried, or it sends its
     * reader to the wrong file.
     */
    @Test
    void candidatesAreReportedMostSpecificFirst(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "one key");
        mock(root, "base", "name=laptop&category=electronics.json", "two keys");

        assertThat(load(root).candidates(fullPayload("base")))
                .containsExactly(
                        "scenarios/base/catalogue/getProduct/name=laptop&category=electronics",
                        "scenarios/base/catalogue/getProduct/name=laptop",
                        "scenarios/base/catalogue/getProduct/_default");
    }

    /** The default is always offered, so a miss names the file an author most often wants to write. */
    @Test
    void theDefaultIsListedEvenWhenNoFileExists(@TempDir Path root) throws IOException {
        scenario(root, "base");

        assertThat(load(root).candidates(fullPayload("base")))
                .containsExactly("scenarios/base/catalogue/getProduct/_default");
    }

    /** A name no request could produce is not a candidate — reachability is what reports it. */
    @Test
    void aNameThatIsNotKeyValueShapedIsNeverACandidate(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "whatever.json", "not addressable");
        mock(root, "base", "_default.json", "fallback");

        assertThat(body(load(root).resolve(fullPayload("base")))).isEqualTo("fallback");
        assertThat(load(root).candidates(fullPayload("base")))
                .noneSatisfy(path -> assertThat(path).contains("whatever"));
    }

    /** A mock saved through the store joins the ranking without a reload. */
    @Test
    void aSavedFileBecomesMatchableImmediately(@TempDir Path root) throws IOException {
        scenario(root, "base");
        FilesystemMockRepository repository = load(root);

        repository.save(
                new com.tao.sandbox.store.MockId("base", SERVICE, OPERATION, "name=laptop.json"),
                com.tao.sandbox.store.MockDocument.of("written"));

        assertThat(body(repository.resolve(fullPayload("base")))).isEqualTo("written");
    }

    @Test
    void aDeletedFileLeavesTheRanking(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "for laptops");
        mock(root, "base", "_default.json", "fallback");
        FilesystemMockRepository repository = load(root);

        repository.delete(new com.tao.sandbox.store.MockId("base", SERVICE, OPERATION, "name=laptop.json"));

        assertThat(body(repository.resolve(fullPayload("base")))).isEqualTo("fallback");
    }

    /** Exact matching is untouched: a subset file is not eligible for it. */
    @Test
    void exactMatchingStillRequiresTheWholeSignature(@TempDir Path root) throws IOException {
        scenario(root, "base");
        mock(root, "base", "name=laptop.json", "subset");
        mock(root, "base", "_default.json", "fallback");

        SequencedMap<String, String> keys = new LinkedHashMap<>();
        keys.put("name", "Laptop");
        keys.put("category", "Electronics");

        // No matching mode given, so EXACT — the four-argument constructor every earlier caller used.
        assertThat(body(load(root).resolve(new MockQuery("base", SERVICE, OPERATION, keys))))
                .isEqualTo("fallback");
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

    private static void scenario(Path root, String id) throws IOException {
        scenarioExtending(root, id, null);
    }

    private static void scenarioExtending(Path root, String id, String parent) throws IOException {
        Path directory = root.resolve("scenarios").resolve(id);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("scenario.yaml"),
                "name: %s\n".formatted(id) + (parent == null ? "" : "extends: %s\n".formatted(parent)),
                StandardCharsets.UTF_8);
    }

    private static void mock(Path root, String scenarioId, String fileName, String body) throws IOException {
        Path directory = root.resolve("scenarios").resolve(scenarioId).resolve(SERVICE).resolve(OPERATION);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), body, StandardCharsets.UTF_8);
    }

    /** The brief's payload, with keys in declaration order. */
    private static MockQuery fullPayload(String scenarioId) {
        return query(scenarioId, "id", "1001", "name", "Laptop", "category", "Electronics", "price", "999.99");
    }

    private static MockQuery query(String scenarioId, String... pairs) {
        SequencedMap<String, String> keys = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            keys.put(pairs[i], pairs[i + 1]);
        }
        return new MockQuery(scenarioId, SERVICE, OPERATION, keys, MockQuery.Matching.BEST);
    }

    private static String body(Optional<MockProvider.Resolved> resolved) {
        return resolved.orElseThrow().document().body();
    }
}
