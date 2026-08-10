package com.tao.sandbox.control;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.control.view.SandboxStatus;
import com.tao.sandbox.control.view.SummaryView;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.MockSummary;
import com.tao.sandbox.store.Scenario;
import com.tao.sandbox.validate.MockStates;
import com.tao.sandbox.validate.MockValidationSweep;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the sandbox is, and re-reading the store.
 *
 * <p>Everything here lives under {@code /__tao}, the reserved control-panel prefix. A dropped-in
 * customer contract can declare any path it likes — including {@code /services} — and a control
 * panel sharing that namespace would mean one of the two silently wins.
 */
@RestController
@RequestMapping(value = "/__tao", produces = MediaType.APPLICATION_JSON_VALUE)
class StatusController {

    private final SandboxProperties properties;
    private final MockRepository repository;
    private final SpecRegistry registry;
    private final ActiveScenario activeScenario;
    private final MockStates states;
    private final MockValidationSweep sweep;

    StatusController(
            SandboxProperties properties,
            MockRepository repository,
            SpecRegistry registry,
            ActiveScenario activeScenario,
            MockStates states,
            MockValidationSweep sweep) {
        this.properties = properties;
        this.repository = repository;
        this.registry = registry;
        this.activeScenario = activeScenario;
        this.states = states;
        this.sweep = sweep;
    }

    @GetMapping("/status")
    SandboxStatus status() {
        return describe();
    }

    /** Headline numbers for the dashboard, in one call. See {@link SummaryView}. */
    @GetMapping("/summary")
    SummaryView summary() {
        int withoutSchema =
                (int) registry.services().stream().filter(service -> !registry.hasSchema(service.id())).count();

        int mockCount = 0;
        int invalid = 0;
        int incomplete = 0;
        int unchecked = 0;
        long largest = 0;

        for (Scenario scenario : repository.scenarios()) {
            for (MockSummary mock : repository.list(scenario.id(), null)) {
                if (mock.inherited()) {
                    continue; // owned elsewhere; counting it here would double-count the file
                }
                mockCount++;
                largest = Math.max(largest, mock.sizeBytes());

                switch (states.get(mock.id()).state()) {
                    case INVALID -> invalid++;
                    case INCOMPLETE -> incomplete++;
                    // Counted, not ignored: it is what the other two counts are silent about.
                    case UNCHECKED -> unchecked++;
                    case VALID -> {}
                }
            }
        }

        return new SummaryView(
                registry.services().size(),
                withoutSchema,
                repository.scenarios().size(),
                activeScenario.get(),
                mockCount,
                invalid,
                incomplete,
                unchecked,
                largest);
    }

    /**
     * Re-reads scenarios and mocks from the store, and answers with the new status.
     *
     * <p>Explicit rather than watched: a mounted network share gives no change notification, and
     * behaviour that works on a laptop but not in the deployed instance is worse than none.
     *
     * <p>Specs are deliberately not re-read. Routes are registered from them at startup, so a
     * changed spec means a changed route table — a restart, not a reload, and pretending otherwise
     * would leave the registry and the router disagreeing.
     */
    @PostMapping("/reload")
    SandboxStatus reload() {
        repository.reload();

        // Every cached verdict described a file that may have changed underneath us, so they all
        // go — and the library is checked again before answering, because a reload is exactly the
        // moment somebody wants to know what the files they just pulled in actually contain, and
        // the status returned here is where they will look for it.
        states.clear();
        sweep.sweep("reload");

        return describe();
    }

    private SandboxStatus describe() {
        return new SandboxStatus(
                properties.store(),
                repository.location(),
                activeScenario.get(),
                properties.scenario().header(),
                registry.services().size(),
                List.of());
    }
}
