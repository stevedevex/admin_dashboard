import { describe, expect, it } from 'vitest';
import { bucketActivity, peakOf } from './activity';

/** Epoch millis for a round number, so expectations read as offsets from it. */
const NOW = Date.parse('2026-01-01T12:00:00.000Z');

const entry = (offsetMs: number, matched: string | null = 'baseline/a/b/c.json') => ({
  at: new Date(NOW - offsetMs).toISOString(),
  matched,
});

const WINDOW = { now: NOW, bucketMs: 1000, buckets: 5 };

describe('bucketActivity', () => {
  it('returns every slice, including empty ones', () => {
    const series = bucketActivity([], WINDOW);

    expect(series).toHaveLength(5);
    expect(series.every((bucket) => bucket.total === 0)).toBe(true);
  });

  it('orders slices oldest first', () => {
    const series = bucketActivity([], WINDOW);
    const times = series.map((bucket) => bucket.at);

    expect(times).toEqual([...times].sort((a, b) => a - b));
    expect(series[0]?.at).toBe(NOW - 5000);
  });

  it('places an entry in the slice covering it', () => {
    // 2.5s ago, in a 5×1s window, is the fourth slice: [-5,-4,-3,-2,-1].
    const series = bucketActivity([entry(2500)], WINDOW);

    expect(series.map((bucket) => bucket.total)).toEqual([0, 0, 1, 0, 0]);
  });

  it('counts an unmatched call as missed as well as total', () => {
    const series = bucketActivity([entry(500, null)], WINDOW);

    expect(series.at(-1)).toMatchObject({ total: 1, missed: 1 });
  });

  it('ignores entries older than the window', () => {
    const series = bucketActivity([entry(9000)], WINDOW);

    expect(series.every((bucket) => bucket.total === 0)).toBe(true);
  });

  it('clamps an entry newer than now into the last slice, rather than dropping it', () => {
    // A client clock a little ahead of the server's must not hide the newest calls.
    const series = bucketActivity([entry(-3000)], WINDOW);

    expect(series.at(-1)?.total).toBe(1);
  });

  it('ignores an unparsable timestamp instead of throwing', () => {
    const series = bucketActivity([{ at: 'not a date', matched: null }], WINDOW);

    expect(series.every((bucket) => bucket.total === 0)).toBe(true);
  });
});

describe('peakOf', () => {
  it('never returns zero, so an empty window still divides', () => {
    expect(peakOf(bucketActivity([], WINDOW))).toBe(1);
  });

  it('reports the tallest slice', () => {
    const series = bucketActivity([entry(500), entry(600), entry(2500)], WINDOW);

    expect(peakOf(series)).toBe(2);
  });
});
