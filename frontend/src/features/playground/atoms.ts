import { atom } from 'jotai';

/**
 * The request being composed, and the answer to the last one sent.
 *
 * Held in atoms rather than component state so it survives navigating away and back. Somebody who
 * leaves the playground to fix the mock a response just revealed as wrong is coming straight back
 * to send the same request again — losing the envelope they had assembled would make the fix the
 * expensive part of the loop.
 *
 * The response deliberately lives here too. It is what a returning reader wants to compare against,
 * and clearing it on unmount would leave the page looking like nothing had ever been sent.
 */

/** Absent means SOAP: the envelope in `bodyAtom` identifies its own operation and endpoint. */
export const methodAtom = atom<string>('GET');

export const pathAtom = atom<string>('');

export const bodyAtom = atom<string>('');

/**
 * Which scenario to send against, independent of what the sandbox serves everyone else.
 *
 * Empty means "whatever is active" rather than a named scenario, so the playground agrees with the
 * sandbox by default and only diverges when somebody says to.
 */
export const scenarioAtom = atom<string>('');
