import { useEffect, useState } from 'react';

/**
 * Wall-clock time as a value that advances on a schedule, not on every render.
 *
 * Reading the clock during render would tie the chart's window to whenever React happened to
 * re-render it: an unrelated state change elsewhere on the page would slide the window sideways,
 * and two panels reading it in the same pass could disagree. Ticking it deliberately also gives
 * the behaviour the chart actually wants — bars drift left while nothing is arriving, rather than
 * freezing until the next call comes in.
 */
export function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);

  return now;
}
