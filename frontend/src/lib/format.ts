/** Pure formatting helpers. No React, no I/O. */

const UNITS = ['B', 'kB', 'MB', 'GB'] as const;

/**
 * Byte counts for display. Payload size is a first-class signal here — a
 * multi-megabyte mock behaves differently from a small one — so it is shown
 * rather than hidden behind a tooltip.
 */
export function formatBytes(bytes: number): string {
  let value = bytes;
  let unit = 0;

  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }

  const decimals = unit === 0 || value >= 100 ? 0 : 1;
  return `${value.toFixed(decimals)} ${UNITS[unit]}`;
}

/**
 * Local wall-clock time: the reader is comparing this against their own run, not against UTC.
 *
 * `millis` is for the log, where two calls a frame apart must be distinguishable; summaries drop
 * them, since three digits of noise on every row buys nothing at a glance.
 */
export function formatClockTime(iso: string, options: { millis?: boolean } = {}): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;

  const clock = at.toLocaleTimeString(undefined, { hour12: false });
  return options.millis ? `${clock}.${String(at.getMilliseconds()).padStart(3, '0')}` : clock;
}
