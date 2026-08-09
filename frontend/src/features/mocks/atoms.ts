import { atom } from 'jotai';

/** Feature-scoped state. Nothing outside `features/mocks` reads this. */

export const selectedMockIdAtom = atom<string | null>(null);

/**
 * The scenario currently being *browsed*.
 *
 * Not the scenario the sandbox serves. Browsing is a view; serving is server
 * state, changed deliberately on the Scenarios page. Keeping them separate is
 * why this atom is scoped to this feature rather than sitting in global chrome:
 * opening a page must never change what the platform receives.
 */
export const viewedScenarioAtom = atom<string>('baseline');

/**
 * Text pasted into the request pane. A probe, never persisted — it exists to
 * derive a file name, not to be stored alongside the response.
 */
export const requestProbeAtom = atom<string>('');

/**
 * Unsaved edits, keyed by mock id.
 *
 * Held per file rather than per selection so switching files and coming back
 * preserves your work. That removes the need to block navigation with a
 * "discard changes?" prompt — nothing is ever silently lost.
 */
export const draftsAtom = atom<Record<string, string>>({});

/**
 * Calls to store beside a mock when it is first saved, keyed by mock id.
 *
 * Only ever populated by arriving from the request log, and only consumed by the save that
 * creates the file. Held separately from the draft because it is not something anyone edits —
 * it is what the mock was written for, not part of what it answers.
 */
export const provenanceAtom = atom<Record<string, string>>({});

/**
 * Incremented to force a refetch. Included in the dependency list of the
 * loading hooks, so bumping it is what the Reload button does.
 */
export const reloadNonceAtom = atom<number>(0);
