package com.tao.sandbox.validate;

import com.tao.sandbox.store.MockId;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * What validation has learned about each mock, remembered so the tree can show it.
 *
 * <p>Populated lazily, by validation actually happening — never by a sweep at startup. Checking a
 * whole library against its schemas before answering anything would delay a service whose selling
 * point is being quick to stand up, and on a large library the delay is not small.
 *
 * <p>The consequence is that a mock nobody has validated reports {@code unchecked}, and the
 * dashboard must draw that as a state rather than as a clean bill of health. That is the honest
 * trade: a mock displaying as valid when nothing checked it is the failure this whole mechanism
 * exists to prevent.
 *
 * <p>In memory and dropped on restart, like the request log. It is derived data — everything here
 * can be recomputed from the payload and its schema.
 */
@Component
public class MockStates {

    /**
     * What is known about one mock.
     *
     * <p>An enum rather than the free strings this used to hold. The values are compared in three
     * places — the summary counts, the sweep's problem tally, and the tree — and a typo in any of
     * them silently miscounts rather than failing, which is the one way this mechanism can mislead
     * without anybody noticing.
     */
    public enum State {
        /** Nothing has looked at it. Never to be drawn as a clean bill of health. */
        UNCHECKED,
        VALID,
        INVALID,
        INCOMPLETE;

        /**
         * Lowercase on the wire. The dashboard has always received these in lowercase and renders
         * them directly, so the JSON shape is held fixed here rather than at each view.
         */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param completeness null whenever nothing declared any fields to be complete against */
    public record Assessment(State state, Integer completeness) {

        static final Assessment UNKNOWN = new Assessment(State.UNCHECKED, null);
    }

    private final Map<String, Assessment> byId = new ConcurrentHashMap<>();

    public Assessment get(MockId id) {
        return byId.getOrDefault(id.asPath(), Assessment.UNKNOWN);
    }

    /** Remembers what a validation run concluded, and returns it. */
    public Assessment record(MockId id, Validation validation) {
        Assessment assessment = assess(validation);
        byId.put(id.asPath(), assessment);
        return assessment;
    }

    /**
     * Forgets one mock, because its content changed.
     *
     * <p>Called on every write and delete. A cached verdict that outlived the payload it described
     * is worse than no verdict: it is a wrong one, shown with confidence.
     */
    public void invalidate(MockId id) {
        byId.remove(id.asPath());
    }

    /** Forgets everything, because the store was re-read and any file may have changed underneath. */
    public void clear() {
        byId.clear();
    }

    private Assessment assess(Validation validation) {
        if (validation.checked() != Validation.Checked.SCHEMA) {
            // Only a schema check learns anything about shape. NONE means no parser applied;
            // SYNTAX means it parsed and nothing judged it — an operation declaring no response
            // body, or a payload that declares itself an error and so is not the shape the
            // contract describes. Reporting either as valid would make "nothing assessed this"
            // indistinguishable from "we checked it against the contract and it passed", which is
            // the one confusion this whole mechanism exists to prevent.
            //
            // A payload that would not parse is still worth saying out loud, though: that is a
            // fact about the bytes, not about any schema.
            return validation.valid()
                    ? Assessment.UNKNOWN
                    : new Assessment(State.INVALID, validation.completeness());
        }
        if (!validation.valid()) {
            return new Assessment(State.INVALID, validation.completeness());
        }
        if (validation.completeness() != null && validation.completeness() < 100) {
            return new Assessment(State.INCOMPLETE, validation.completeness());
        }
        return new Assessment(State.VALID, validation.completeness());
    }
}
