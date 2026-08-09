import type { RequestEntry } from '@/api';
import { Icon, Tag } from '@/ui';
import styles from './RequestTable.module.css';

export type RequestTableProps = {
  entries: RequestEntry[];
  selectedId: string | null;
  onSelect: (id: string) => void;
};

/** Local wall-clock time: the reader is comparing this against their own run, not against UTC. */
function timeOf(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime())
    ? iso
    : at.toLocaleTimeString(undefined, { hour12: false }) +
        '.' +
        String(at.getMilliseconds()).padStart(3, '0');
}

export function RequestTable({ entries, selectedId, onSelect }: RequestTableProps) {
  return (
    <div className={styles.scroll}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th className={styles.time}>Time</th>
            <th className={styles.service}>Service</th>
            <th className={styles.operation}>Operation</th>
            <th className={`${styles.status} ${styles.numeric}`}>Status</th>
            <th className={`${styles.took} ${styles.numeric}`}>Took</th>
            <th>Answered by</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr
              key={entry.id}
              className={entry.id === selectedId ? styles.rowActive : styles.row}
              onClick={() => onSelect(entry.id)}
            >
              <td className={`${styles.time} ${styles.mono}`}>{timeOf(entry.at)}</td>
              <td className={styles.service}>{entry.serviceId ?? '—'}</td>
              <td className={`${styles.operation} ${styles.mono}`}>{entry.operationId ?? '—'}</td>
              <td className={`${styles.status} ${styles.numeric}`}>
                {/* 2xx is unremarkable and stays plain; anything else is why someone is here. */}
                {entry.status >= 400 ? (
                  <Tag tone={entry.status >= 500 ? 'error' : 'warn'}>{entry.status}</Tag>
                ) : (
                  <span className={styles.mono}>{entry.status}</span>
                )}
              </td>
              <td className={`${styles.took} ${styles.numeric}`}>{entry.tookMillis} ms</td>
              <td className={styles.answered}>
                {entry.matched ? (
                  <span className={styles.mono} title={entry.matched}>
                    {entry.inherited && <Icon name="scenarios" size={11} />} {fileOf(entry.matched)}
                  </span>
                ) : (
                  <Tag tone="warn" icon={<Icon name="warn" size={11} />}>
                    no mock matched
                  </Tag>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The file name alone, with the full path on hover.
 *
 * A matched id is `scenario/service/operation/file`, and the first three are already columns on
 * this row — repeating them would push the part that differs off the edge.
 */
function fileOf(matched: string): string {
  return matched.split('/').pop() ?? matched;
}
