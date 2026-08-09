package com.tao.sandbox.control;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.control.view.SandboxStatus;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.validate.MockStates;
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

    StatusController(
            SandboxProperties properties,
            MockRepository repository,
            SpecRegistry registry,
            ActiveScenario activeScenario,
            MockStates states) {
        this.properties = properties;
        this.repository = repository;
        this.registry = registry;
        this.activeScenario = activeScenario;
        this.states = states;
    }

    @GetMapping("/status")
    SandboxStatus status() {
        return describe();
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
        // Every cached verdict described a file that may have changed underneath us.
        states.clear();
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
