import { atomWithStorage } from 'jotai/utils';

/**
 * Shell state — genuinely global, so it lives here rather than in a feature.
 * Anything used by a single feature belongs in that feature's `atoms.ts`.
 */

/** Rail collapsed to icons only. Persisted: a layout preference should stick. */
export const railCollapsedAtom = atomWithStorage('tao.rail.collapsed', false);
