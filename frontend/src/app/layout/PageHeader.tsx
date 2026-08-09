import type { ReactNode } from 'react';
import styles from './PageHeader.module.css';

export type PageHeaderProps = {
  title: string;
  /** Context strip — `MetaTag`s and status tags describing what is on screen. */
  meta?: ReactNode;
  /** Right-aligned buttons. */
  actions?: ReactNode;
};

export function PageHeader({ title, meta, actions }: PageHeaderProps) {
  return (
    <header className={styles.header}>
      <h1 className={styles.title}>{title}</h1>
      {meta ? <div className={styles.meta}>{meta}</div> : null}
      {actions ? <div className={styles.actions}>{actions}</div> : null}
    </header>
  );
}
