import { peakOf, type ActivityBucket } from '@/lib/activity';
import styles from './Sparkline.module.css';

export type SparklineProps = {
  series: ActivityBucket[];
  /** Names the window on screen — see `bucketActivity`, this chart is a peephole, not a history. */
  windowLabel: string;
};

/**
 * Call volume over the window, with misses stacked on top of each bar.
 *
 * Drawn as elements rather than a chart library: it is a dozen rectangles, and a dependency that
 * ships a layout engine to place them would be the largest thing on the page. Heights are inline
 * because they are data; every colour comes from a token.
 *
 * Misses are stacked in the same bar rather than shown as a second series. A miss is not traffic
 * of a different kind, it is the same call failing to resolve — so the eye should read one bar
 * whose top is red, not two bars to compare.
 */
export function Sparkline({ series, windowLabel }: SparklineProps) {
  const peak = peakOf(series);
  const total = series.reduce((sum, bucket) => sum + bucket.total, 0);
  const missed = series.reduce((sum, bucket) => sum + bucket.missed, 0);

  return (
    <div
      className={styles.chart}
      role="img"
      aria-label={`${total} calls in the ${windowLabel}, ${missed} of them unmatched`}
    >
      {series.map((bucket) => (
        <div key={bucket.at} className={styles.slot}>
          <div className={styles.bar} style={{ height: `${(bucket.total / peak) * 100}%` }}>
            {bucket.missed > 0 ? (
              <div
                className={styles.missed}
                style={{ height: `${(bucket.missed / bucket.total) * 100}%` }}
              />
            ) : null}
          </div>
        </div>
      ))}
    </div>
  );
}
