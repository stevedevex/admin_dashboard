import { Link } from 'react-router';
import type { RequestEntry } from '@/api';
import { bucketActivity } from '@/lib/activity';
import { formatClockTime } from '@/lib/format';
import { EmptyState, Icon, Panel, Tag } from '@/ui';
import { useNow } from '../hooks/useNow';
import { Sparkline } from './Sparkline';
import styles from './ActivityPanel.module.css';

/** Five minutes in twenty slices: fine enough to see a burst, coarse enough to stay readable. */
const BUCKET_MS = 15_000;
const BUCKETS = 20;
const WINDOW_LABEL = 'last 5 minutes';

/** How many calls to list. The full log is one click away; this is the tail of it. */
const LISTED = 7;

export type ActivityPanelProps = {
  entries: RequestEntry[];
  live: boolean;
  /** True once the server's buffer wrapped past our cursor, so some calls were never seen. */
  sampled: boolean;
  error: Error | null;
};

/**
 * What the sandbox is being asked for, as it happens.
 *
 * The one panel here that moves. A mock server is otherwise entirely static on screen, and the
 * question its users actually arrive with — "is my application even reaching this thing?" — is
 * answered by traffic appearing, not by any count of what exists.
 *
 * Misses are given the emphasis rather than volume: a served call is the expected case and needs
 * no attention, while a call nothing answered is the one thing on this page worth acting on.
 */
export function ActivityPanel({ entries, live, sampled, error }: ActivityPanelProps) {
  // Advanced one slice at a time, so the window slides even through a silence — a chart frozen on
  // the last call it saw would report old traffic as current.
  const now = useNow(BUCKET_MS);

  // Recomputed each render rather than memoised: the input is capped at a few hundred entries and
  // polling already re-renders this subtree, so a cache here would cost more than it saves.
  const series = bucketActivity(entries, { now, bucketMs: BUCKET_MS, buckets: BUCKETS });

  const recent = entries.slice(0, LISTED);
  const missed = entries.filter((entry) => entry.matched === null).length;

  return (
    <Panel
      title="Live activity"
      actions={
        <>
          {sampled ? <Tag tone="warn">sampled</Tag> : null}
          {error ? (
            <Tag tone="error">log unreachable</Tag>
          ) : (
            <Tag tone={live ? 'ok' : 'neutral'}>{live ? 'live' : 'paused'}</Tag>
          )}
          <Link to="/mock-data/requests" className={styles.more}>
            Full log
            <Icon name="requests" size={13} />
          </Link>
        </>
      }
    >
      {entries.length === 0 ? (
        <EmptyState title="No calls yet">
          Point an application at this sandbox and its requests appear here as they arrive — served
          and unserved alike.
        </EmptyState>
      ) : (
        <div className={styles.body}>
          <div className={styles.chartHead}>
            <span className={styles.window}>{WINDOW_LABEL}</span>
            {missed > 0 ? (
              <span className={styles.missedCount}>
                {missed} unmatched of {entries.length} retained
              </span>
            ) : (
              <span className={styles.window}>{entries.length} retained</span>
            )}
          </div>

          <Sparkline series={series} windowLabel={WINDOW_LABEL} />

          <ul className={styles.feed}>
            {recent.map((entry) => (
              <li key={entry.id} className={styles.entry}>
                <span className={styles.time}>{formatClockTime(entry.at)}</span>
                <span className={styles.target}>
                  <span className={styles.service}>{entry.serviceId ?? 'unrouted'}</span>
                  <span className={styles.operation}>{entry.operationId ?? '—'}</span>
                </span>
                <span className={styles.status}>{entry.status}</span>
                {entry.matched === null ? (
                  <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
                    no mock
                  </Tag>
                ) : (
                  <span className={styles.matched} title={entry.matched}>
                    {entry.matched.split('/').pop()}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </Panel>
  );
}
