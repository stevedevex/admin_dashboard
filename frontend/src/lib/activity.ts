/** Shaping the request log for display. No React, no I/O. */

/** One slice of wall-clock time, and what the log holds for it. */
export type ActivityBucket = {
  /** Start of the slice, epoch millis. */
  at: number;
  total: number;
  /** Calls no mock answered — the reason this chart is worth a glance. */
  missed: number;
};

/**
 * Lay the log out over a fixed window of equal slices, oldest first.
 *
 * This describes *the entries it was handed*, never "all traffic": the server keeps a bounded
 * in-memory buffer that is dropped on restart, so a quiet chart means "nothing in the buffer for
 * that slice", which is not the same claim. Callers must label the window on screen — an unlabelled
 * histogram reads as history, and this one is a peephole.
 *
 * Slices are returned even when empty, so the chart keeps a stable width and a gap in traffic
 * stays visible as a gap rather than closing up.
 */
export function bucketActivity(
  entries: readonly { at: string; matched: string | null }[],
  window: { now: number; bucketMs: number; buckets: number },
): ActivityBucket[] {
  const { now, bucketMs, buckets } = window;
  const start = now - bucketMs * buckets;

  const series: ActivityBucket[] = Array.from({ length: buckets }, (_, slice) => ({
    at: start + slice * bucketMs,
    total: 0,
    missed: 0,
  }));

  for (const entry of entries) {
    const at = Date.parse(entry.at);
    if (Number.isNaN(at) || at < start) continue;

    // Clamped rather than dropped: a client clock running slightly ahead of the server's would
    // otherwise make the newest calls — the ones being watched — vanish off the end.
    const slice = Math.min(buckets - 1, Math.floor((at - start) / bucketMs));
    const bucket = series[slice];
    if (bucket === undefined) continue;

    bucket.total += 1;
    if (entry.matched === null) bucket.missed += 1;
  }

  return series;
}

/** The tallest slice, as the chart's ceiling. Never zero, so an empty window still divides. */
export function peakOf(series: readonly ActivityBucket[]): number {
  return Math.max(1, ...series.map((bucket) => bucket.total));
}
