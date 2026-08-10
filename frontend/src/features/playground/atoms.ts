import { atom } from 'jotai';
import type { PlaygroundResult } from '@/api';

/**
 * The request being composed, and the answer to the last one sent.
 *
 * Held in atoms rather than component state so it survives navigating away and back. Somebody who
 * leaves the playground to fix the mock a response just revealed as wrong is coming straight back
 * to send the same request again — losing the envelope they had assembled would make the fix the
 * expensive part of the loop.
 *
 * The response deliberately lives here too. It is what a returning reader wants to compare against,
 * and clearing it on unmount would leave the page looking like nothing had ever been sent — which
 * is the state the page is in at exactly the moment the round trip completes.
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

/**
 * What came back from the last request sent, or null before any has been.
 *
 * The half of this that is easy to get wrong: a response held in component state disappears the
 * moment somebody navigates away — and the reason they navigate away is almost always to act on
 * what the response told them. They return to a page claiming nothing was ever sent, with no way
 * back to the answer they left to go and fix.
 */
export const resultAtom = atom<PlaygroundResult | null>(null);

/**
 * What the draft could not fill in, kept beside the request it describes.
 *
 * Not with the response: it explains the request, so it must survive for as long as that request is
 * on screen, and go the moment a different one replaces it.
 */
export const noteAtom = atom<string | null>(null);
