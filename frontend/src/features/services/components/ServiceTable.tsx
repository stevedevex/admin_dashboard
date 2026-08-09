import type { Service } from '@/api';
import { Tag } from '@/ui';
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
            <td className={styles.mono}>{service.keyFields.join(', ') || '—'}</td>
            <td className={styles.numeric}>{service.mockCount}</td>
            <td>{service.hasSchema ? <Tag tone="ok">yes</Tag> : <Tag tone="neutral">none</Tag>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
