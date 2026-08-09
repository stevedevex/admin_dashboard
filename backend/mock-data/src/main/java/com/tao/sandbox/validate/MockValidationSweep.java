package com.tao.sandbox.validate;

import com.tao.sandbox.config.SandboxProperties;
import com.tao.sandbox.store.MockDocument;
import com.tao.sandbox.store.MockRepository;
import com.tao.sandbox.store.MockSummary;
import com.tao.sandbox.store.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Checks every mock the store holds, so a verdict describes the library rather than the clicking.
 *
 * <p>Verdicts used to be recorded only where a reader pressed Validate. That made the tree's marks
 * a record of what somebody had looked at this session, not of what is wrong — and since the store
 * is a shared mount that other people and other instances write to, "what somebody looked at" is
 * the less useful of the two by a wide margin. A file arriving from a colleague's branch is
 * precisely the file nobody here has checked.
 *
 * <p>Run after the application is serving, not during startup. Standing a sandbox up quickly is
 * the point of it, and on a large library this is not instant; the alternative — validating
 * nothing until asked — is what this replaces. So requests are answered from the first moment and
 * the marks fill in behind them, over seconds rather than minutes.
 *
 * <p>Run again after a reload, because a reload exists precisely to pick up files that changed
 * underneath, and a verdict for a payload that has since been replaced is not merely stale but
 * wrong — asserted with confidence about bytes that are gone.
 *
 * <p>What it does not do is watch the store. A mounted share gives no change notification, so
 * between reloads the verdict describes what this instance would actually serve, which is the
 * loaded content — not whatever the file says on disk. That is the honest reading of it, and the
 * useful one: a mark answers "is what I am serving right", not "is the file on the share right".
 */
@Component
public class MockValidationSweep {

    private static final Logger log = LoggerFactory.getLogger(MockValidationSweep.class);

    private final MockRepository repository;
    private final MockValidator validator;
    private final MockStates states;
    private final SandboxProperties properties;

    MockValidationSweep(
            MockRepository repository,
            MockValidator validator,
            MockStates states,
            SandboxProperties properties) {
        this.repository = repository;
        this.validator = validator;
        this.states = states;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.verdicts().onStartup()) {
            log.info("Startup validation is off; mocks read as unchecked until something checks them");
            return;
        }

        // The only sweep nobody is waiting on, and the only one worth putting on another thread.
        // A virtual thread because the application already runs on them.
        Thread.ofVirtual().name("mock-validation-sweep").start(() -> sweep("startup"));
    }

    /**
     * Check everything, on the calling thread.
     *
     * <p>Synchronous, because the callers that are not startup asked for this: somebody pressed
     * reload and is holding the response open precisely to be told what the files they just pulled
     * in contain. Answering before the answer exists would hand them the previous verdicts, or
     * none, and leave the real ones to appear some time later with nothing to say they had.
     */
    public void sweep(String because) {
        long started = System.currentTimeMillis();
        int checked = 0;
        int problems = 0;

        for (Scenario scenario : repository.scenarios()) {
            for (MockSummary summary : repository.list(scenario.id(), null)) {
                // Inherited files are owned by the scenario they live in and are checked there.
                // Validating them once per scenario that sees them would be the same work repeated
                // and the same verdict recorded under a borrowed id.
                if (summary.inherited()) {
                    continue;
                }

                if (check(summary)) {
                    problems++;
                }
                checked++;
            }
        }

        log.info(
                "Validated {} mock(s) on {} in {}ms — {} with something to report",
                checked,
                because,
                System.currentTimeMillis() - started,
                problems);
    }

    /** @return true when the mock is anything other than clean, for the summary line */
    private boolean check(MockSummary summary) {
        try {
            MockDocument document = repository.get(summary.id()).orElse(null);
            if (document == null) {
                // Deleted between listing and reading. Nothing to say about a file that is gone.
                return false;
            }

            // With the meta, so a mock that declares itself a fault or an error status is not
            // judged against the success shape it was never meant to have.
            Validation validation =
                    validator.validate(
                            summary.id().serviceId(),
                            summary.id().operationId(),
                            document.body(),
                            document.meta());

            String state = states.record(summary.id(), validation).state();

            // Unchecked is not a problem, it is an absence of one: nothing could be checked, so
            // nothing was learned. Only a verdict that was actually reached and was not clean
            // belongs in the count.
            return !MockStates.UNCHECKED.equals(state) && !"valid".equals(state);
        } catch (RuntimeException e) {
            // A file for an operation no longer served, most likely — configuration was edited and
            // the mock outlived it. Left unchecked rather than failing the sweep: the rest of the
            // library still deserves marks, and this one has a more visible problem than its
            // payload.
            log.debug("Could not validate {}: {}", summary.id().asPath(), e.getMessage());
            return false;
        }
    }
}
