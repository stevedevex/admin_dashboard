import type { ReactNode } from 'react';
import styles from './Tag.module.css';

/** Status vocabulary, deliberately generic — `ok` not `valid`, so any feature can use it. */
export type TagTone = 'neutral' | 'info' | 'ok' | 'warn' | 'error' | 'accent';

export type TagProps = {
  tone?: TagTone;
  icon?: ReactNode;
  /**
   * Hover text. Required in practice whenever a tag is rendered icon-only: a glyph with no
   * label and no tooltip is undiscoverable, and readers guess — usually wrongly.
   */
  title?: string;
  children: ReactNode;
};

export function Tag({ tone = 'neutral', icon, title, children }: TagProps) {
  return (
    <span className={`${styles.tag} ${styles[tone]}`} title={title}>
      {icon ? <span className={styles.icon}>{icon}</span> : null}
      {children}
    </span>
  );
}

/** Label/value pair, as used in a page header's context strip. */
export function MetaTag({ label, value }: { label: string; value: ReactNode }) {
  return (
    <span className={styles.meta}>
      <span className={styles.metaLabel}>{label}</span>
      <span className={styles.metaValue}>{value}</span>
    </span>
  );
}
