package com.tao.sandbox.store.fs;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockId;
import com.tao.sandbox.store.MockQuery;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.MockSummary;
import com.tao.sandbox.store.Scenario;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Mocks as files on disk, served from memory.
 *
 * <p>Files, because the mock set is then git-versionable and diffable: a scenario is a directory,
 * a change is a reviewable diff, and reproducing a colleague's run is a checkout.
 *
 * <p>Memory, because the sandbox also serves load tests: the resolve path must do no file I/O.
 * The whole library is read once at startup and on every explicit reload, and every request is
 * answered from the index. Writes made through {@link #save} and {@link #delete} update the index
 * as they land; a file edited on disk behind the store's back becomes visible on the next
 * {@code POST /__tao/reload} — the same explicit-reload contract a mounted share forces anyway,
 * since SMB gives no change notification.
 *
 * <pre>
 * &lt;root&gt;/scenarios/&lt;scenario&gt;/scenario.yaml
 *                              /&lt;service&gt;/&lt;operation&gt;/&lt;key=value&amp;…&gt;.&lt;ext&gt;
 *                                                     /_default.&lt;ext&gt;
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "tao.sandbox.store", havingValue = "filesystem", matchIfMissing = true)
public class FilesystemMockRepository implements MockRepository {

    private static final Logger log = LoggerFactory.getLogger(FilesystemMockRepository.class);

    private final Path root;

    /**
     * Both maps are replaced wholesale on {@link #reload} and mutated in place by writes, so a
     * request racing a reload sees either the old library or the new one — never a half-built one.
     */
    private volatile Map<String, Scenario> scenarios = new ConcurrentHashMap<>();

    /** Keyed by {@code scenario/service/operation/stem} — the address resolution actually uses. */
    private volatile Map<String, CachedMock> mocks = new ConcurrentHashMap<>();

    /** One mock held whole: payload, sidecars, and the metadata browsing shows. */
    private record CachedMock(MockId id, MockDocument document, long sizeBytes, Instant modifiedAt) {}

    public FilesystemMockRepository(SandboxProperties properties) {
        this.root = Path.of(properties.filesystem().root()).toAbsolutePath().normalize();
    }

    @PostConstruct
    @Override
    public void reload() {
        Map<String, Scenario> loadedScenarios = new ConcurrentHashMap<>();
        Map<String, CachedMock> loadedMocks = new ConcurrentHashMap<>();

        Path scenarioRoot = root.resolve("scenarios");
        if (!Files.isDirectory(scenarioRoot)) {
            log.warn("No scenarios directory at {} — the sandbox will answer nothing", scenarioRoot);
            this.scenarios = loadedScenarios;
            this.mocks = loadedMocks;
            return;
        }

        try (Stream<Path> directories = Files.list(scenarioRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                Scenario scenario = readScenario(directory);
                loadedScenarios.put(scenario.id(), scenario);
                loadMocks(directory, scenario.id(), loadedMocks);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + scenarioRoot, e);
        }

        detectInheritanceCycles(loadedScenarios);

        this.scenarios = loadedScenarios;
        this.mocks = loadedMocks;
        log.info(
                "Loaded {} scenario(s), {} mock(s) from {}",
                loadedScenarios.size(),
                loadedMocks.size(),
                scenarioRoot);
    }

    @Override
    public Optional<Resolved> resolve(MockQuery query) {
        // Memory only — this is the hot path, and it must stay free of file I/O.
        //
        // The filename is the outer dimension, matching candidates() and the resolution spec: an
        // exact key match anywhere in the chain beats a nearer _default, because the better
        // address wins wherever it was found. (An earlier version walked scenario-first, which
        // let an active scenario's _default shadow an inherited exact match.)
        for (String fileName : fileNames(query)) {
            for (String scenarioId : chain(query.scenarioId())) {
                CachedMock cached = mocks.get(key(scenarioId, query.serviceId(), query.operationId(), fileName));
                if (cached != null) {
                    return Optional.of(
                            new Resolved(
                                    cached.id(),
                                    cached.document(),
                                    scenarioId,
                                    !scenarioId.equals(query.scenarioId())));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Candidate paths, most specific first, across the whole inheritance chain.
     *
     * <p>Specificity runs outermost: a {@code _default} in the active scenario does not beat an
     * exact key match inherited from its parent. The better address wins wherever it was found.
     */
    @Override
    public List<String> candidates(MockQuery query) {
        List<String> paths = new ArrayList<>();
        for (String fileName : fileNames(query)) {
            for (String scenarioId : chain(query.scenarioId())) {
                paths.add(
                        "scenarios/%s/%s/%s/%s"
                                .formatted(scenarioId, query.serviceId(), query.operationId(), fileName));
            }
        }
        return paths;
    }

    @Override
    public List<MockSummary> list(String scenarioId, String serviceId) {
        List<MockSummary> summaries = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();

        for (String current : chain(scenarioId)) {
            for (CachedMock cached : ownedBy(current)) {
                MockId id = cached.id();

                if (serviceId != null && !serviceId.equals(id.serviceId())) {
                    continue;
                }

                // A nearer scenario overrides the same slot in an ancestor.
                String slot = id.serviceId() + "/" + id.operationId() + "/" + id.fileName();
                if (!claimed.add(slot)) {
                    continue;
                }

                summaries.add(summarise(scenarioId, cached));
            }
        }

        return summaries;
    }

    @Override
    public Optional<MockDocument> get(MockId id) {
        CachedMock cached = mocks.get(keyFor(id));
        if (cached != null && cached.id().fileName().equals(id.fileName())) {
            return Optional.of(cached.document());
        }

        // Not indexed — most likely a file created on disk since the last reload. Reading it here
        // keeps the editor honest about what exists, without putting disk I/O anywhere near
        // resolution; serving it still requires the reload that makes the whole index agree.
        Path path = pathFor(id.scenarioId(), id.serviceId(), id.operationId(), id.fileName());
        return Files.isRegularFile(path) ? Optional.of(read(path)) : Optional.empty();
    }

    @Override
    public MockSummary save(MockId id, MockDocument document) {
        requireScenario(id.scenarioId());

        Path path = pathFor(id.scenarioId(), id.serviceId(), id.operationId(), id.fileName());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, document.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }

        Sidecars.write(path, document.envelopeHeader(), document.meta());

        CachedMock cached = load(id, path);
        mocks.put(keyFor(id), cached);
        return summarise(id.scenarioId(), cached);
    }

    @Override
    public void delete(MockId id) {
        try {
            Path payload = pathFor(id.scenarioId(), id.serviceId(), id.operationId(), id.fileName());
            Files.deleteIfExists(payload);
            // Stale sidecars would silently re-apply to whatever is saved next.
            for (Path sidecar : Sidecars.allFor(payload)) {
                Files.deleteIfExists(sidecar);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + id.asPath(), e);
        }

        mocks.remove(keyFor(id));
    }

    @Override
    public List<Scenario> scenarios() {
        return scenarios.values().stream().sorted(Comparator.comparing(Scenario::id)).toList();
    }

    @Override
    public Scenario createScenario(String id, String name, String description, String parent) {
        requireSegment(id);

        if (scenarios.containsKey(id)) {
            throw new IllegalStateException("Scenario '" + id + "' already exists");
        }
        if (parent != null && !scenarios.containsKey(parent)) {
            throw new IllegalArgumentException(
                    "Cannot extend '%s': it does not exist. Scenarios: %s".formatted(parent, scenarios.keySet()));
        }

        // A new scenario cannot close a cycle unless it extends itself — every other ancestor
        // already existed and was acyclic — but the check is cheap and states the rule once.
        if (id.equals(parent)) {
            throw new IllegalArgumentException("A scenario cannot extend itself");
        }

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", name == null || name.isBlank() ? id : name);
        descriptor.put("description", description == null ? "" : description);
        if (parent != null) {
            descriptor.put("extends", parent);
        }

        Path directory = root.resolve("scenarios").resolve(id);
        try {
            Files.createDirectories(directory);
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Files.writeString(
                    directory.resolve("scenario.yaml"), new Yaml(options).dump(descriptor), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + directory, e);
        }

        Scenario created = readScenario(directory);
        scenarios.put(id, created);
        return created;
    }

    @Override
    public void deleteScenario(String id) {
        requireScenario(id);

        String child =
                scenarios.values().stream()
                        .filter(scenario -> id.equals(scenario.parent()))
                        .map(Scenario::id)
                        .findFirst()
                        .orElse(null);

        if (child != null) {
            // Orphaning a child would silently change what it serves: every mock it inherited
            // disappears, and the scenario keeps running with a quietly smaller library.
            throw new IllegalStateException("'%s' is extended by '%s'".formatted(id, child));
        }

        Path directory = root.resolve("scenarios").resolve(id);
        try (Stream<Path> contents = Files.walk(directory)) {
            // Deepest first, because a directory cannot be removed until it is empty.
            for (Path path : contents.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + directory, e);
        }

        scenarios.remove(id);
        mocks.keySet().removeIf(key -> key.startsWith(id + "/"));
    }

    @Override
    public String location() {
        return root.toString();
    }

    // --- the index ----------------------------------------------------------

    /** Reads every mock a scenario owns into the index being built. */
    private void loadMocks(Path scenarioDirectory, String scenarioId, Map<String, CachedMock> into) {
        try (Stream<Path> files = Files.walk(scenarioDirectory)) {
            // Sorted so that two files sharing a stem — legal but pointless — resolve the same
            // way on every platform, instead of by directory-listing order.
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.equals("scenario.yaml") || Sidecars.isSidecar(fileName)) {
                    continue;
                }

                Path relative = scenarioDirectory.relativize(path);
                if (relative.getNameCount() != 3) {
                    continue;
                }

                MockId id =
                        new MockId(
                                scenarioId,
                                relative.getName(0).toString(),
                                relative.getName(1).toString(),
                                fileName);
                into.putIfAbsent(keyFor(id), load(id, path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + scenarioDirectory, e);
        }
    }

    private CachedMock load(MockId id, Path path) {
        try {
            return new CachedMock(id, read(path), Files.size(path), Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stat " + path, e);
        }
    }

    private List<CachedMock> ownedBy(String scenarioId) {
        String prefix = scenarioId + "/";
        return mocks.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    /**
     * The extension is not part of the address: a service's payload format is a property of the
     * contract, not of the request, so any file with the right stem is the mock.
     */
    private static String key(String scenarioId, String serviceId, String operationId, String stem) {
        return scenarioId + "/" + serviceId + "/" + operationId + "/" + stem;
    }

    private static String keyFor(MockId id) {
        return key(id.scenarioId(), id.serviceId(), id.operationId(), stripExtension(id.fileName()));
    }

    // --- internals ----------------------------------------------------------

    /**
     * Writing into a scenario that does not exist would create its directory as a side effect, and
     * a scenario with no {@code scenario.yaml} inherits from nothing — so the mock would resolve
     * only for callers naming that scenario exactly, which is never what was meant.
     */
    private void requireScenario(String scenarioId) {
        if (!scenarios.containsKey(scenarioId)) {
            throw new IllegalArgumentException(
                    "No such scenario '%s'. Scenarios: %s".formatted(scenarioId, scenarios.keySet()));
        }
    }

    /** The id becomes a directory name, so it has to be one. */
    private static void requireSegment(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A scenario needs an id");
        }
        if (id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("Illegal scenario id '" + id + "': it names one directory");
        }
    }

    /** Scenario ids from the requested one up through its ancestors, nearest first. */
    private List<String> chain(String scenarioId) {
        List<String> ordered = new ArrayList<>();
        String current = scenarioId;

        while (current != null && !ordered.contains(current)) {
            ordered.add(current);
            Scenario scenario = scenarios.get(current);
            current = scenario == null ? null : scenario.parent();
        }

        return ordered;
    }

    /** Filename stems, most specific first. */
    private List<String> fileNames(MockQuery query) {
        String signature = query.keySignature();
        return signature.isEmpty() ? List.of(DEFAULT_STEM) : List.of(signature, DEFAULT_STEM);
    }

    private Path pathFor(String scenarioId, String serviceId, String operationId, String fileName) {
        return root.resolve("scenarios")
                .resolve(scenarioId)
                .resolve(serviceId)
                .resolve(operationId)
                .resolve(fileName);
    }

    private MockDocument read(Path path) {
        try {
            return new MockDocument(
                    Files.readString(path, StandardCharsets.UTF_8),
                    Sidecars.readEnvelopeHeader(path),
                    Sidecars.readMeta(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private MockSummary summarise(String requestedScenario, CachedMock cached) {
        String foundIn = cached.id().scenarioId();
        return new MockSummary(
                cached.id(),
                cached.sizeBytes(),
                cached.modifiedAt(),
                !foundIn.equals(requestedScenario),
                foundIn.equals(requestedScenario) ? null : foundIn);
    }

    private Scenario readScenario(Path directory) {
        String id = directory.getFileName().toString();
        Path descriptor = directory.resolve("scenario.yaml");

        String name = id;
        String description = "";
        String parent = null;

        if (Files.isRegularFile(descriptor)) {
            try {
                Map<String, Object> raw = new Yaml().load(Files.readString(descriptor, StandardCharsets.UTF_8));
                if (raw != null) {
                    name = String.valueOf(raw.getOrDefault("name", id));
                    description = String.valueOf(raw.getOrDefault("description", ""));
                    Object extendsValue = raw.get("extends");
                    parent = extendsValue == null ? null : String.valueOf(extendsValue);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + descriptor, e);
            }
        }

        return new Scenario(id, name, description, parent);
    }

    /**
     * A cycle would make {@link #chain} terminate but silently truncate, so it is rejected at load
     * time where the message can name the scenarios involved.
     */
    private static void detectInheritanceCycles(Map<String, Scenario> scenarios) {
        for (Scenario scenario : scenarios.values()) {
            Set<String> seen = new LinkedHashSet<>();
            String current = scenario.id();

            while (current != null) {
                if (!seen.add(current)) {
                    throw new IllegalStateException(
                            "Scenario inheritance cycle: " + String.join(" -> ", seen) + " -> " + current);
                }
                Scenario next = scenarios.get(current);
                current = next == null ? null : next.parent();
            }
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
