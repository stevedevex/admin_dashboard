import type { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import styles from './Input.module.css';

/**
 * Form controls.
 *
 * Thin on purpose: they exist so no feature reaches for a bare `<input>` and reinvents the
 * borders, focus ring and disabled treatment — the things that go subtly inconsistent first.
 *
 * `mono` is a real distinction rather than decoration. An id that becomes a directory name, a
 * path, a file name: these are read character by character, and a proportional font is where a
 * stray space or a lookalike character hides.
 */
export type TextInputProps = InputHTMLAttributes<HTMLInputElement> & { mono?: boolean };

export function TextInput({ mono = false, className, ...rest }: TextInputProps) {
  return <input className={classes(styles.input, mono && styles.mono, className)} {...rest} />;
}

export type TextAreaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & { mono?: boolean };

export function TextArea({ mono = false, className, rows = 2, ...rest }: TextAreaProps) {
  return (
    <textarea rows={rows} className={classes(styles.input, mono && styles.mono, className)} {...rest} />
  );
}

export type SelectProps = SelectHTMLAttributes<HTMLSelectElement>;

export function Select({ className, children, ...rest }: SelectProps) {
  return (
    <select className={classes(styles.input, styles.select, className)} {...rest}>
      {children}
    </select>
  );
}

function classes(...values: (string | false | undefined)[]): string {
  return values.filter(Boolean).join(' ');
}
