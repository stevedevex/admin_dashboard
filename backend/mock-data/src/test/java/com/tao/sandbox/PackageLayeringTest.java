package com.tao.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The layering the package docs describe, checked rather than asserted in prose.
 *
 * <p>Every package carries a note saying what it is for and what it sits above. Those notes are the
 * fastest way for somebody new to trace how a request is wired, and they are worth exactly as much
 * as their accuracy — a documented layering that nothing enforces is one refactor from being a lie,
 * and a confidently wrong map is worse than no map.
 *
 * <p>So the two claims that carry weight are checked here: that dependencies run one way, and that
 * the control plane is a leaf. Both are read off the source, which is the only description that
 * cannot drift from itself.
 */
class PackageLayeringTest {

    private static final Path SOURCE = Path.of("src/main/java/com/tao/sandbox");
    private static final String ROOT = "com.tao.sandbox";

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT =
            Pattern.compile("^import (?:static )?(com\\.tao\\.sandbox[\\w.]*)\\.\\w+;", Pattern.MULTILINE);

    /**
     * The one cycle the module has, named so it cannot be mistaken for the shape of the whole.
     *
     * <p>{@code spec} holds the registry and the types a loaded contract becomes; {@code
     * spec.openapi} and {@code spec.wsdl} hold the loaders. The registry calls the loaders and the
     * loaders build the registry's types, so each pair points both ways.
     *
     * <p>Left as it is, for now. Breaking it means moving {@code OperationDefinition}, {@code
     * ServedOperation}, {@code ServiceDescriptor} and {@code KeyDescriptor} into a package beneath
     * both — worth doing, and not worth doing as a side effect of writing documentation. Named
     * here because an exception that is written down is a decision, while one folded into a looser
     * check is a blind spot: any cycle other than these two still fails.
     */
    private static final Set<String> ACCEPTED =
            Set.of(ROOT + ".spec <-> " + ROOT + ".spec.openapi", ROOT + ".spec <-> " + ROOT + ".spec.wsdl");

    /**
     * A cycle between packages is the change worth resisting: it means two packages have to be
     * understood together, and the diagram in the root package note stops being readable top to
     * bottom.
     */
    @Test
    void dependenciesBetweenPackagesRunOneWay() {
        Map<String, Set<String>> graph = dependencies();
        List<String> cycles = new ArrayList<>();

        graph.keySet().forEach(from -> findCycle(from, from, graph, new LinkedHashSet<>(), cycles));

        List<String> unexpected = cycles.stream().filter(cycle -> !ACCEPTED.contains(normalise(cycle))).toList();

        assertThat(unexpected).as("package dependency cycles beyond the accepted ones").isEmpty();
    }

    /** The accepted set is a record of what is true; a stale entry would hide a fix. */
    @Test
    void everyAcceptedCycleStillExists() {
        Map<String, Set<String>> graph = dependencies();
        List<String> cycles = new ArrayList<>();

        graph.keySet().forEach(from -> findCycle(from, from, graph, new LinkedHashSet<>(), cycles));
        Set<String> present = cycles.stream().map(PackageLayeringTest::normalise).collect(java.util.stream.Collectors.toSet());

        assertThat(present).as("accepted cycles that have since been broken — remove them").containsAll(ACCEPTED);
    }

    /** Two-package cycles are one fact reported four ways; reduce each to an unordered pair. */
    private static String normalise(String cycle) {
        List<String> hops = List.of(cycle.split(" -> "));
        List<String> pair = hops.subList(0, hops.size() - 1).stream().sorted().toList();
        return String.join(" <-> ", pair);
    }

    /**
     * Nothing may depend on the control plane. It is where the dashboard's API lives, and anything
     * below reaching up into it would mean request serving depends on the panel that observes it.
     */
    @Test
    void nothingDependsOnTheControlPlane() {
        Map<String, Set<String>> graph = dependencies();

        List<String> reachingUp =
                graph.entrySet().stream()
                        .filter(entry -> !entry.getKey().startsWith(ROOT + ".control"))
                        .filter(entry -> entry.getValue().stream().anyMatch(to -> to.startsWith(ROOT + ".control")))
                        .map(Map.Entry::getKey)
                        .toList();

        assertThat(reachingUp).isEmpty();
    }

    /** Every package explains itself, because the notes are the map. */
    @Test
    void everyPackageCarriesANote() throws IOException {
        List<String> undocumented = new ArrayList<>();

        for (String pkg : dependencies().keySet()) {
            Path directory = SOURCE.resolve(pkg.substring(ROOT.length()).replace(".", "/").replaceFirst("^/", ""));
            if (!Files.isRegularFile(directory.resolve("package-info.java"))) {
                undocumented.add(pkg);
            }
        }

        assertThat(undocumented).isEmpty();
    }

    // --- reading the source -------------------------------------------------

    private static Map<String, Set<String>> dependencies() {
        Map<String, Set<String>> graph = new LinkedHashMap<>();

        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);

                Matcher declared = PACKAGE.matcher(source);
                if (!declared.find()) {
                    continue;
                }
                String pkg = declared.group(1);
                Set<String> to = graph.computeIfAbsent(pkg, key -> new LinkedHashSet<>());

                Matcher imports = IMPORT.matcher(source);
                while (imports.find()) {
                    // An import of a nested type reads as a longer package; the owning one is
                    // whichever known package it starts with, resolved once the graph is whole.
                    if (!imports.group(1).equals(pkg)) {
                        to.add(imports.group(1));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SOURCE.toAbsolutePath(), e);
        }

        // Nested-type imports (`…store.MockDocument.Kind`) name no package. Fold each onto the
        // longest declared package that prefixes it, so the graph has only real edges.
        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        graph.forEach(
                (from, targets) -> {
                    Set<String> owning = new LinkedHashSet<>();
                    for (String target : targets) {
                        graph.keySet().stream()
                                .filter(candidate -> target.equals(candidate) || target.startsWith(candidate + "."))
                                .max((a, b) -> Integer.compare(a.length(), b.length()))
                                .filter(candidate -> !candidate.equals(from))
                                .ifPresent(owning::add);
                    }
                    resolved.put(from, owning);
                });

        assertThat(resolved).as("packages found under " + SOURCE.toAbsolutePath()).isNotEmpty();
        return resolved;
    }

    private static void findCycle(
            String start, String current, Map<String, Set<String>> graph, Set<String> path, List<String> cycles) {

        if (!path.add(current)) {
            if (current.equals(start)) {
                cycles.add(String.join(" -> ", path) + " -> " + current);
            }
            return;
        }

        graph.getOrDefault(current, Set.of()).forEach(next -> findCycle(start, next, graph, path, cycles));
        path.remove(current);
    }
}
