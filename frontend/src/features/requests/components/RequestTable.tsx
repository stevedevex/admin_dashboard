import type { RequestEntry } from '@/api';
import { formatClockTime } from '@/lib/format';
import { fileOf } from '@/lib/mockPath';
import { Icon, Tag } from '@/ui';
import styles from './RequestTable.module.css';

export type RequestTableProps = {
  entries: RequestEntry[];
  selectedId: string | null;
  onSelect: (id: string) => void;
};

export function RequestTable({ entries, selectedId, onSelect }: RequestTableProps) {
  return (
    <div className={styles.scroll}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th className={styles.time}>Time</th>
            <th className={styles.source}>From</th>
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
              <td className={`${styles.time} ${styles.mono}`}>
                {formatClockTime(entry.at, { millis: true })}
              </td>
              {/* The application under test is the expected case and stays quiet; a call somebody
                  made by hand is the one that would otherwise be misread as traffic. */}
              <td className={styles.source}>
                {entry.source === 'playground' ? (
                  <Tag tone="info" icon={<Icon name="playground" size={11} />}>
                    playground
                  </Tag>
                ) : (
                  <span className={styles.muted}>app</span>
                )}
              </td>
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
              {/* Sub-millisecond is the normal case for a mock served from memory, and a column
                  of "0 ms" is a column of noise. Shown when there is something to notice. */}
              <td className={`${styles.took} ${styles.numeric}`}>
                {entry.tookMillis > 0 ? `${entry.tookMillis} ms` : <span className={styles.muted}>—</span>}
              </td>
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
