import { Link } from 'react-router';
import styles from './MetricStrip.module.css';

export type Metric = {
  label: string;
  /** Pre-formatted: this component decides weight and alignment, never units. */
  value: string;
  /** A second line under the figure — what it is of, or what is wrong with it. */
  note?: string;
  /** Draws the note as a warning. For counts that are only interesting when non-zero. */
  attention?: boolean;
  /** Makes the tile a link into the page that can act on the figure. */
  to?: string;
};

/**
 * The headline figures, as one band of equal tiles.
 *
 * Divided by hairlines rather than spaced apart as separate cards: these are facets of one
 * library, and boxing each of them up implies they are separate things to compare. A single
 * ruled band is also how a reader scans a row of numbers without re-reading the label each time.
 */
export function MetricStrip({ metrics }: { metrics: Metric[] }) {
  return (
    <section className={styles.strip}>
      {metrics.map((metric) => {
        const body = (
          <>
            <span className={styles.label}>{metric.label}</span>
            <span className={styles.value}>{metric.value}</span>
            <span className={metric.attention ? styles.noteAttention : styles.note}>
              {/* Held even when empty, so tiles with a note do not sit taller than those without. */}
              {metric.note || ' '}
            </span>
          </>
        );

        return metric.to ? (
          <Link key={metric.label} to={metric.to} className={styles.tileLink}>
            {body}
          </Link>
        ) : (
          <div key={metric.label} className={styles.tile}>
            {body}
          </div>
        );
      })}
    </section>
  );
}
