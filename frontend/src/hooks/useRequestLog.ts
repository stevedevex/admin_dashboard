import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type RequestEntry } from '@/api';

/** How often to ask for what is new. Two seconds of staleness is invisible in a dev tool. */
const POLL_MS = 2000;

/**
 * How many entries to keep on screen.
 *
 * The server's buffer is the real bound; this one stops a long session growing the DOM without
 * limit. Kept well under the server default so what is dropped here was already scrolled past.
 */
const RETAIN = 300;

export type RequestLogState = {
  entries: RequestEntry[];
  /** True once the buffer has wrapped past our cursor, so some calls were never seen. */
  sampled: boolean;
  live: boolean;
  error: Error | null;
  setLive: (live: boolean) => void;
  clear: () => void;
};

/**
 * The request log, polled.
 *
 * Polling rather than a stream: two seconds of staleness is invisible in a development tool, and
 * polling survives every corporate proxy that would quietly break an SSE connection — a debugging
 * aid that itself needs debugging is worth less than nothing.
 *
 * Each poll asks only for what arrived after the last cursor, so a log that is mostly unchanged
 * costs an empty answer rather than a full refetch. Newest first on screen, because the reason to
 * open this page is almost always the request that just failed.
 */
export function useRequestLog(): RequestLogState {
  const [entries, setEntries] = useState<RequestEntry[]>([]);
  const [sampled, setSampled] = useState(false);
  const [live, setLive] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  /** Held in a ref, not state: it changes on every poll and must not itself trigger a render. */
  const cursor = useRef<string | undefined>(undefined);

  const clear = useCallback(() => {
    setEntries([]);
    setSampled(false);
  }, []);

  useEffect(() => {
    if (!live) return;

    let cancelled = false;
    let timer: number | undefined;

    const poll = async () => {
      try {
        const page = await api.listRequests(
          cursor.current === undefined ? {} : { since: cursor.current },
        );
        if (cancelled) return;

        cursor.current = page.cursor;
        setError(null);
        if (page.mode === 'sampled') setSampled(true);

        if (page.entries.length > 0) {
          // Newest first, and capped. Reversed because the server answers oldest first — the
          // order a cursor implies, and the opposite of the order a reader wants.
          setEntries((current) => [...page.entries.toReversed(), ...current].slice(0, RETAIN));
        }
      } catch (cause) {
        if (!cancelled) setError(cause instanceof Error ? cause : new Error(String(cause)));
      } finally {
        // Chained rather than on an interval: a slow or failed answer must not let requests pile
        // up behind it, and a backend that is down should be retried, not hammered.
        if (!cancelled) timer = window.setTimeout(poll, POLL_MS);
      }
    };

    void poll();

    return () => {
      cancelled = true;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [live]);

  return { entries, sampled, live, error, setLive, clear };
}
