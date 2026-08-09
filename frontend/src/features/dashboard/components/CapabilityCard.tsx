import { Link } from 'react-router';
import { Icon, Tag, type IconName, type TagTone } from '@/ui';
import styles from './CapabilityCard.module.css';

export type CardStat = { label: string; value: string; mono?: boolean };
export type CardNote = { tone: TagTone; text: string };

export type CapabilityCardProps = {
  title: string;
  description: string;
  icon: IconName;
  /** `null` renders the card as present but not yet navigable. */
  to: string | null;
  state: 'loading' | 'ready' | 'error';
  error: string | null;
  stats: CardStat[];
  notes: CardNote[];
};

/**
 * One capability of the sandbox, summarised.
 *
 * Every card takes the same shape whatever the capability, so a later phase
 * adds one without this component learning anything about it.
 */
export function CapabilityCard({
  title,
  description,
  icon,
  to,
  state,
  error,
  stats,
  notes,
}: CapabilityCardProps) {
  const body = (
    <>
      <div className={styles.head}>
        <span className={styles.icon}>
          <Icon name={icon} size={18} />
        </span>
        <div>
          <h2 className={styles.title}>{title}</h2>
          <p className={styles.description}>{description}</p>
        </div>
      </div>

      {state === 'loading' && <p className={styles.status}>Loading…</p>}
      {state === 'error' && <p className={styles.status}>{error}</p>}

      {stats.length > 0 && (
        <dl className={styles.stats}>
          {stats.map((stat) => (
            <div key={stat.label} className={styles.stat}>
              <dt className={styles.statLabel}>{stat.label}</dt>
              <dd className={stat.mono ? styles.statValueMono : styles.statValue}>{stat.value}</dd>
            </div>
          ))}
        </dl>
      )}

      {notes.length > 0 && (
        <div className={styles.notes}>
          {notes.map((note) => (
            <Tag key={note.text} tone={note.tone}>
              {note.text}
            </Tag>
          ))}
        </div>
      )}
    </>
  );

  if (!to) return <section className={styles.cardIdle}>{body}</section>;

  return (
    <Link to={to} className={styles.card}>
      {body}
    </Link>
  );
}
