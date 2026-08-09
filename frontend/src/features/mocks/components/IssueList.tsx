import type { ValidationResult } from '@/api';
import { Icon, Tag } from '@/ui';
import styles from './IssueList.module.css';

/**
 * Validation output.
 *
 * The `checked` level is always stated. A payload that merely parses must not
 * read as fully approved, or a schema-invalid mock looks clean and the
 * validation is worse than none.
 */
export function IssueList({ result }: { result: ValidationResult }) {
  if (result.checked === 'none') {
    return (
      <div className={styles.bar}>
        <Tag tone="neutral" icon={<Icon name="unknown" size={12} />}>
          no validator for this format
        </Tag>
      </div>
    );
  }

  if (result.valid) {
    return (
      <div className={styles.bar}>
        <Tag tone="ok" icon={<Icon name="ok" size={12} />}>
          {result.checked === 'schema' ? 'valid against schema' : 'well-formed'}
        </Tag>
        {result.checked === 'syntax' && (
          <span className={styles.caveat}>syntax only — not checked against a schema</span>
        )}
      </div>
    );
  }

  return (
    <ul className={styles.list}>
      {result.issues.map((issue, index) => (
        <li key={`${issue.path}-${index}`} className={styles.issue}>
          <span className={styles.icon}>
            <Icon name="error" size={13} />
          </span>
          <span className={styles.line}>{issue.line === null ? '—' : `line ${issue.line}`}</span>
          <span className={styles.message}>{issue.message}</span>
          <span className={styles.path}>{issue.path}</span>
        </li>
      ))}
    </ul>
  );
}
