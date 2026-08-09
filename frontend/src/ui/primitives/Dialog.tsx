import * as RadixDialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';
import styles from './Dialog.module.css';

export type DialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  /** One line under the title. Say what the dialog does, not what the button said. */
  description?: string;
  /** Buttons, laid out trailing-edge first by the footer itself. */
  footer?: ReactNode;
  children: ReactNode;
};

/**
 * A modal.
 *
 * Wraps Radix rather than hand-rolling one: a dialog is mostly the behaviour nobody sees until it
 * is missing — focus trapped inside and restored on close, Escape, the rest of the page hidden
 * from assistive technology, scroll locked. Getting those wrong is not a styling bug, it is a
 * keyboard user unable to leave.
 *
 * The title is required and always rendered: an unlabelled modal is one a screen reader announces
 * as nothing at all.
 */
export function Dialog({ open, onOpenChange, title, description, footer, children }: DialogProps) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className={styles.overlay} />
        <RadixDialog.Content className={styles.content}>
          <RadixDialog.Title className={styles.title}>{title}</RadixDialog.Title>
          {description ? (
            <RadixDialog.Description className={styles.description}>
              {description}
            </RadixDialog.Description>
          ) : (
            // Radix warns when a content has no description; saying explicitly that there is none
            // is quieter than an empty element and honest to a screen reader.
            <RadixDialog.Description className={styles.hidden}>{title}</RadixDialog.Description>
          )}

          <div className={styles.body}>{children}</div>

          {footer ? <div className={styles.footer}>{footer}</div> : null}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}

/** A labelled field. Layout only — the control is whatever the caller passes. */
export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <label className={styles.field}>
      <span className={styles.label}>{label}</span>
      {children}
      {hint ? <span className={styles.hint}>{hint}</span> : null}
    </label>
  );
}
