package com.tao.sandbox.runtime.resolve;

import com.tao.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

/**
 * Which scenario the sandbox serves when a request does not ask for one.
 *
 * <p>Seeded from configuration and changeable at runtime, because switching scenarios is the whole
 * point of having them and restarting to do it makes the feature unusable mid-session.
 *
 * <p>Held in one place rather than read from {@link SandboxProperties} at each call site: this is
 * the only mutable piece of what would otherwise be immutable configuration, and letting it stay
 * bound to the properties record would mean either mutating configuration — which is bound once at
 * startup precisely so it cannot drift — or having two answers to the same question.
 *
 * <p>Deliberately not persisted. It is a runtime override of a configured default, so a restart
 * returns to what the configuration says; a change that survived restart would leave a deployed
 * instance serving something no file explains.
 */
@Component
public class ActiveScenario {

    /** Volatile rather than synchronised: one reference, written rarely, read on every request. */
    private volatile String current;

    public ActiveScenario(SandboxProperties properties) {
        this.current = properties.scenario().active();
    }

    public String get() {
        return current;
    }

    public void set(String scenarioId) {
        this.current = scenarioId;
    }
}
