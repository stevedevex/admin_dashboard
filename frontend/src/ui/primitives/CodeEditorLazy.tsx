import { lazy, Suspense } from 'react';
import type { CodeEditorProps } from './CodeEditor';
import styles from './CodeEditor.module.css';

/**
 * The editor is roughly two thirds of the application bundle and is only
 * reached on one page, so it is split into its own chunk and loaded on demand.
 *
 * Callers import `CodeEditor` from `@/ui` and are unaware of the split.
 */
const Impl = lazy(() => import('./CodeEditor').then((module) => ({ default: module.CodeEditor })));

export function CodeEditor(props: CodeEditorProps) {
  return (
    <Suspense fallback={<div className={styles.loading}>Loading editor…</div>}>
      <Impl {...props} />
    </Suspense>
  );
}
