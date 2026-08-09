package com.tao.sandbox.control;

import com.tao.sandbox.control.view.ActiveScenarioRequest;
import com.tao.sandbox.control.view.ScenarioRequest;
import com.tao.sandbox.control.view.ScenarioView;
import com.tao.sandbox.runtime.resolve.ActiveScenario;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.Scenario;
import com.tao.sandbox.validate.MockStates;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The scenarios the store holds, and how much each one owns. */
@RestController
@RequestMapping(value = "/__tao/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
class ScenarioCatalogController {

    private final MockRepository repository;
    private final ActiveScenario activeScenario;
    private final MockStates states;

    ScenarioCatalogController(
            MockRepository repository, ActiveScenario activeScenario, MockStates states) {
        this.repository = repository;
        this.activeScenario = activeScenario;
        this.states = states;
    }

    @GetMapping
    List<ScenarioView> scenarios() {
        return repository.scenarios().stream()
                .map(
                        scenario ->
                                new ScenarioView(
                                        scenario.id(),
                                        scenario.name(),
                                        scenario.description(),
                                        scenario.parent(),
                                        ownedMockCount(scenario.id())))
                .toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ScenarioView> create(@RequestBody ScenarioRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw ControlPanelProblem.badRequest("missing-id", "Missing id", "A scenario needs an id");
        }

        Scenario created;
        try {
            created =
                    repository.createScenario(
                            request.id(), request.name(), request.description(), request.parent());
        } catch (IllegalStateException e) {
            throw ControlPanelProblem.conflict("scenario-exists", "Scenario already exists", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ControlPanelProblem.unprocessable("unusable-parent", "Cannot extend that", e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new ScenarioView(
                                created.id(),
                                created.name(),
                                created.description(),
                                created.parent(),
                                ownedMockCount(created.id())));
    }

    /**
     * The one endpoint that changes what the application under test receives.
     *
     * <p>Everything else in the control panel is browsing. On a shared instance this affects every
     * caller at once — which is why the dashboard shows the served scenario as a read-only fact
     * rather than as a page-level picker somebody changes without noticing who else is connected.
     */
    @PutMapping(value = "/active", consumes = MediaType.APPLICATION_JSON_VALUE)
    ScenarioView setActive(@RequestBody ActiveScenarioRequest request) {
        String scenarioId = request == null ? null : request.scenarioId();

        Scenario scenario =
                repository.scenarios().stream()
                        .filter(candidate -> candidate.id().equals(scenarioId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        ControlPanelProblem.unprocessable(
                                                "scenario-not-found",
                                                "No such scenario",
                                                "'%s' is not one of %s"
                                                        .formatted(scenarioId, ids())));

        activeScenario.set(scenario.id());

        return new ScenarioView(
                scenario.id(),
                scenario.name(),
                scenario.description(),
                scenario.parent(),
                ownedMockCount(scenario.id()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        if (id.equals(activeScenario.get())) {
            // Deleting what is being served would leave the sandbox answering nothing, with the
            // configuration still naming a scenario that no longer exists.
            throw ControlPanelProblem.conflict(
                    "scenario-in-use",
                    "Scenario is being served",
                    "'%s' is the active scenario. Switch to another one first.".formatted(id));
        }

        try {
            repository.deleteScenario(id);
        } catch (IllegalStateException e) {
            throw ControlPanelProblem.conflict("scenario-extended", "Scenario has a child", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ControlPanelProblem.notFound("scenario-not-found", "No such scenario", e.getMessage());
        }

        // Every verdict for a mock in that scenario described a file that is now gone.
        states.clear();

        return ResponseEntity.noContent().build();
    }

    /**
     * Inherited mocks are excluded: they belong to the ancestor that stores them, and counting
     * them here would report the same file once per scenario in the chain.
     */
    private int ownedMockCount(String scenarioId) {
        return (int) repository.list(scenarioId, null).stream().filter(mock -> !mock.inherited()).count();
    }

    private List<String> ids() {
        return repository.scenarios().stream().map(Scenario::id).toList();
    }
}
