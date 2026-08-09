import type { MockState, MockSummary } from '@/api';

/**
 * Order of concern, worst first.
 *
 * `invalid` outranks `incomplete` because one is a payload the contract rejects and the other is a
 * payload it accepts with gaps. `unchecked` outranks `valid` — not because it is worse, but
 * because it is *unknown*, and an operation shown as clean when nobody has looked is the exact
 * confusion the state vocabulary exists to prevent.
 */
const BY_CONCERN: MockState[] = ['invalid', 'incomplete', 'unchecked', 'valid'];

/**
 * One state for a whole operation: the most concerning of its files. Pure — no React, no I/O.
 *
 * Rolling up rather than counting, because the question a reader has when scanning a list of
 * operations is "is there anything here I need to look at", and a tally of four states per row
 * answers it more slowly than a single mark.
 */
export function worstState(mocks: readonly MockSummary[]): MockState {
  if (mocks.length === 0) return 'unchecked';

  for (const state of BY_CONCERN) {
    if (mocks.some((mock) => mock.state === state)) return state;
  }

  return 'valid';
}
