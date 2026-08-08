package com.tao.sandbox.spec;

import java.util.List;

/**
 * Startup failure, carrying every problem found rather than the first.
 *
 * <p>Reporting one error at a time turns a misconfigured service into a sequence of restarts. The
 * whole list is cheap to collect and far cheaper to act on.
 */
public class SpecLoadException extends RuntimeException {

    private final List<String> problems;

    public SpecLoadException(List<String> problems) {
        super(
                "Sandbox configuration is not usable:%n%s"
                        .formatted(String.join(System.lineSeparator(), problems.stream().map(p -> "  - " + p).toList())));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
