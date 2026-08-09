import type { ReactNode } from 'react';
import styles from './Panel.module.css';

export type PanelProps = {
  title?: ReactNode;
  /** Rendered at the right of the header — actions, toggles, a status tag. */
  actions?: ReactNode;
  footer?: ReactNode;
  /** Removes body padding, for panels holding an editor or a table. */
  flush?: boolean;
  children: ReactNode;
};

export function Panel({ title, actions, footer, flush = false, children }: PanelProps) {
  return (
    <section className={styles.panel}>
      {title || actions ? (
        <header className={styles.header}>
          <h2 className={styles.title}>{title}</h2>
          {actions ? <div className={styles.actions}>{actions}</div> : null}
        </header>
      ) : null}
      <div className={flush ? styles.bodyFlush : styles.body}>{children}</div>
      {footer ? <footer className={styles.footer}>{footer}</footer> : null}
    </section>
  );
}

/** Centred message for an empty or not-yet-implemented area. */
export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className={styles.empty}>
      <p className={styles.emptyTitle}>{title}</p>
      {children ? <p className={styles.emptyBody}>{children}</p> : null}
    </div>
  );
}
