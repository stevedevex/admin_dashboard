import type { KeyField, Service } from '@/api';
import { Tag, Tooltip } from '@/ui';
import styles from './ServiceTable.module.css';

/**
 * `protocol` is free-form text from the backend, so tone is derived rather
 * than switched on a fixed list — a new protocol must not need a code change.
 */
const PROTOCOL_TONES = ['info', 'ok', 'accent', 'warn'] as const;

function protocolTone(protocol: string) {
  let hash = 0;
  for (const char of protocol) hash = (hash + char.charCodeAt(0)) % PROTOCOL_TONES.length;
  return PROTOCOL_TONES[hash] ?? 'neutral';
}

/**
 * One declared identity field.
 *
 * The name alone is enough while it is the field's own — `tickerSymbol` reads as what it is. An
 * alias is not: `brid` is a short name configuration chose, and on a page whose whole purpose is
 * to tell a reader what identifies a request to a service, a name that names nothing is a blank.
 * So what it stands for is on the row, and where it is read from is one hover away.
 */
function KeyFieldCell({ field }: { field: KeyField }) {
  return (
    <Tooltip
      side="top"
      label={
        <>
          <strong>{field.name}</strong>
          {field.aliasOf ? <> — the {field.aliasOf} field</> : null}
          <br />
          {field.source.toLowerCase()} · {field.expression}
        </>
      }
    >
      {/* Focusable so the tooltip is reachable by keyboard: Radix opens on focus as well as
          hover, and a plain span is never focused, which hides where the key is read from from
          anyone not using a mouse. */}
      <span className={styles.key} tabIndex={0}>
        {field.name}
        {field.aliasOf ? <span className={styles.aliasOf}> → {field.aliasOf}</span> : null}
      </span>
    </Tooltip>
  );
}

export function ServiceTable({ services }: { services: Service[] }) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>Service</th>
          <th>Protocol</th>
          <th>Endpoint</th>
          <th>Key fields</th>
          <th className={styles.numeric}>Mocks</th>
          <th>Schema</th>
        </tr>
      </thead>
      <tbody>
        {services.map((service) => (
          <tr key={service.id}>
            <td>
              <span className={styles.name}>{service.name}</span>
              <span className={styles.id}>{service.id}</span>
            </td>
            <td>
              <Tag tone={protocolTone(service.protocol)}>{service.protocol}</Tag>
            </td>
            <td className={styles.mono}>{service.endpoint}</td>
            <td>
              {service.keyFields.length === 0 ? (
                <span className={styles.mono}>—</span>
              ) : (
                <div className={styles.keys}>
                  {service.keyFields.map((field) => (
                    <KeyFieldCell key={field.name} field={field} />
                  ))}
                </div>
              )}
            </td>
            <td className={styles.numeric}>{service.mockCount}</td>
            <td>{service.hasSchema ? <Tag tone="ok">yes</Tag> : <Tag tone="neutral">none</Tag>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
