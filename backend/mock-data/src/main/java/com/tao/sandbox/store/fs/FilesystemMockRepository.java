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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Mocks as files on disk.
 *
 * <p>Chosen first because the mock set is then git-versionable and diffable: a scenario is a
 * directory, a change is a reviewable diff, and reproducing a colleague's run is a checkout.
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
    private static final String DEFAULT_STEM = "_default";

    private final Path root;
    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();

    public FilesystemMockRepository(SandboxProperties properties) {
        this.root = Path.of(properties.filesystem().root()).toAbsolutePath().normalize();
    }

    @PostConstruct
    @Override
    public void reload() {
        scenarios.clear();
        Path scenarioRoot = root.resolve("scenarios");

        if (!Files.isDirectory(scenarioRoot)) {
            log.warn("No scenarios directory at {} — the sandbox will answer nothing", scenarioRoot);
            return;
        }

        try (Stream<Path> directories = Files.list(scenarioRoot)) {
            directories.filter(Files::isDirectory).forEach(this::readScenario);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + scenarioRoot, e);
        }

        detectInheritanceCycles();
        log.info("Loaded {} scenario(s) from {}", scenarios.size(), scenarioRoot);
    }

    @Override
    public Optional<Resolved> resolve(MockQuery query) {
        for (String scenarioId : chain(query.scenarioId())) {
            for (String fileName : fileNames(query)) {
                Path candidate = pathFor(scenarioId, query.serviceId(), query.operationId(), fileName);
                Optional<Path> found = firstExisting(candidate);

                if (found.isPresent()) {
                    MockId id =
                            new MockId(
                                    scenarioId,
                                    query.serviceId(),
                                    query.operationId(),
                                    found.get().getFileName().toString());
                    return Optional.of(
                            new Resolved(
                                    id,
                                    read(found.get()),
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
            Path base = root.resolve("scenarios").resolve(current);
            if (!Files.isDirectory(base)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(base)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().equals("scenario.yaml"))
                        .filter(path -> !Sidecars.isSidecar(path.getFileName().toString()))
                        .forEach(
                                path -> {
                                    Path relative = base.relativize(path);
                                    if (relative.getNameCount() != 3) {
                                        return;
                                    }

                                    String service = relative.getName(0).toString();
                                    String operation = relative.getName(1).toString();
                                    String file = relative.getName(2).toString();

                                    if (serviceId != null && !serviceId.equals(service)) {
                                        return;
                                    }

                                    // A nearer scenario overrides the same slot in an ancestor.
                                    String slot = service + "/" + operation + "/" + file;
                                    if (!claimed.add(slot)) {
                                        return;
                                    }

                                    summaries.add(summarise(scenarioId, current, path, service, operation, file));
                                });
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list " + base, e);
            }
        }

        return summaries;
    }

    @Override
    public Optional<MockDocument> get(MockId id) {
        Path path = pathFor(id.scenarioId(), id.serviceId(), id.operationId(), id.fileName());
        return Files.isRegularFile(path) ? Optional.of(read(path)) : Optional.empty();
    }

    @Override
    public MockSummary save(MockId id, String body) {
        Path path = pathFor(id.scenarioId(), id.serviceId(), id.operationId(), id.fileName());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
        return summarise(
                id.scenarioId(), id.scenarioId(), path, id.serviceId(), id.operationId(), id.fileName());
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
    }

    @Override
    public List<Scenario> scenarios() {
        return List.copyOf(scenarios.values());
    }

    // --- internals ---------------------------------------------------------

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

    /** Filenames without extension, most specific first. */
    private List<String> fileNames(MockQuery query) {
        String signature = query.keySignature();
        return signature.isEmpty() ? List.of(DEFAULT_STEM) : List.of(signature, DEFAULT_STEM);
    }

    /**
     * The extension is not part of the lookup: a service's payload format is a property of the
     * contract, not of the request, so any sibling with the right stem is the match.
     */
    private Optional<Path> firstExisting(Path stem) {
        Path directory = stem.getParent();
        String name = stem.getFileName().toString();

        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }

        try (Stream<Path> siblings = Files.list(directory)) {
            return siblings
                    .filter(Files::isRegularFile)
                    .filter(path -> !Sidecars.isSidecar(path.getFileName().toString()))
                    .filter(path -> stripExtension(path.getFileName().toString()).equals(name))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + directory, e);
        }
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

    private MockSummary summarise(
            String requestedScenario, String foundIn, Path path, String service, String operation, String file) {
        try {
            return new MockSummary(
                    new MockId(foundIn, service, operation, file),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant(),
                    !foundIn.equals(requestedScenario),
                    foundIn.equals(requestedScenario) ? null : foundIn);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stat " + path, e);
        }
    }

    private void readScenario(Path directory) {
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

        scenarios.put(id, new Scenario(id, name, description, parent));
    }

    /**
     * A cycle would make {@link #chain} terminate but silently truncate, so it is rejected at load
     * time where the message can name the scenarios involved.
     */
    private void detectInheritanceCycles() {
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
