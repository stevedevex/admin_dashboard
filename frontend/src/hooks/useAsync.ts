import { useEffect, useState } from 'react';

export type AsyncState<T> =
  | { status: 'loading'; data: null; error: null }
  | { status: 'ready'; data: T; error: null }
  | { status: 'error'; data: null; error: Error };

const LOADING = { status: 'loading', data: null, error: null } as const;

/** Result of a completed load, tagged with the inputs that produced it. */
type Settled<T> = { key: string; state: AsyncState<T> };

/**
 * Minimal async state for reads.
 *
 * Loading is *derived*, not assigned: a result carries the key it was fetched
 * for, and anything stale renders as loading. That keeps `setState` out of the
 * effect body — calling it there triggers cascading renders, which React 19's
 * lint rejects, and which would be a real bug the moment this hook is used
 * more than once on a page.
 *
 * Deliberately small: with the in-memory transport there is nothing to cache,
 * dedupe or retry. When saving lands and lists need invalidating after a
 * mutation, replace this with a query library — the change is contained here
 * and in the hooks that call it, because components only ever see `AsyncState`.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const key = JSON.stringify(deps);
  const [settled, setSettled] = useState<Settled<T> | null>(null);

  useEffect(() => {
    let cancelled = false;

    load()
      .then((data) => {
        if (!cancelled) setSettled({ key, state: { status: 'ready', data, error: null } });
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        const error = cause instanceof Error ? cause : new Error(String(cause));
        setSettled({ key, state: { status: 'error', data: null, error } });
      });

    return () => {
      cancelled = true;
    };
    // `load` is excluded on purpose: callers pass an inline closure, so it is a
    // new reference every render. `key` is the serialised dependency list and
    // is the real trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return settled?.key === key ? settled.state : LOADING;
}
