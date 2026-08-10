import { atom } from 'jotai';

/**
 * A file one page asks another to open.
 *
 * Genuinely global, and only because it crosses a feature boundary: the request log knows which
 * mock a call was asking for, and the mocks page is where one is written. Features must not import
 * each other, so the handover is state rather than a call — and it is a single atom, consumed and
 * cleared on arrival, not a store anything reads twice.
 *
 * @param body a payload to open the editor with, for a mock that does not exist yet. Absent means
 *   "just select this one" — the difference between writing a mock and looking at one.
 * @param request the call that motivated it, saved beside the mock as provenance
 */
export type MockHandoff = {
  mockId: string;
  scenarioId: string;
  body?: string;
  request?: string;
};

export const mockHandoffAtom = atom<MockHandoff | null>(null);

/**
 * A request one page asks the playground to load.
 *
 * The same pattern and for the same reason: three features know about a request worth re-sending —
 * the dry run beside the mock editor, the log of what a client actually called, and the playground
 * that sends them — and none of them may import another.
 *
 * @param send whether to send it on arrival. A call being replayed from the log is one somebody has
 *   already decided about, so it runs; a probe handed over mid-thought is not, so it waits.
 */
export type PlaygroundHandoff = {
  /** Absent means the body is a SOAP envelope, which carries its own endpoint. */
  method?: string;
  path?: string;
  body?: string;
  scenarioId?: string;
  send?: boolean;
};

export const playgroundHandoffAtom = atom<PlaygroundHandoff | null>(null);
