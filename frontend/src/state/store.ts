import { atom } from 'jotai';

/**
 * Incremented whenever the server's own view of things has changed — it re-read the library, or
 * it started serving a different scenario.
 *
 * Global rather than feature-scoped because either change moves what every page is describing:
 * the mock tree, the per-service counts, the dashboard's totals. A nonce per feature would leave
 * whichever page did not own the control showing the library as it was before.
 */
export const storeNonceAtom = atom<number>(0);
