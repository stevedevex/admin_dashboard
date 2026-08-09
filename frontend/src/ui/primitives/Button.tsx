import type { ButtonHTMLAttributes, ReactNode } from 'react';
import styles from './Button.module.css';

/**
 * `emphasis` names intent, not colour — so a different design system can map
 * it onto whatever it calls those levels.
 *
 *   primary   commit within a flow    (Save)
 *   accent    create something new    (New Mock)
 *   secondary cancel, back, export
 *   muted     tertiary, text-only
 */
export type ButtonEmphasis = 'primary' | 'accent' | 'secondary' | 'muted';

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  emphasis?: ButtonEmphasis;
  fullWidth?: boolean;
  icon?: ReactNode;
};

export function Button({
  emphasis = 'secondary',
  fullWidth = false,
  icon,
  children,
  className,
  type = 'button',
  ...rest
}: ButtonProps) {
  const classes = [styles.button, styles[emphasis], fullWidth ? styles.fullWidth : '', className ?? '']
    .filter(Boolean)
    .join(' ');

  return (
    <button type={type} className={classes} {...rest}>
      {icon ? <span className={styles.icon}>{icon}</span> : null}
      {children}
    </button>
  );
}
