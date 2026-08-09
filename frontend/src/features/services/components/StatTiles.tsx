import type { Service } from '@/api';
import styles from './StatTiles.module.css';

type Tile = { label: string; value: string; caption: string };

function summarise(services: Service[]): Tile[] {
  const withSchema = services.filter((s) => s.hasSchema).length;
  const mocks = services.reduce((total, s) => total + s.mockCount, 0);
  const protocols = new Set(services.map((s) => s.protocol));

  return [
    { label: 'Services', value: String(services.length), caption: 'configured' },
    { label: 'Mocks', value: String(mocks), caption: 'across all scenarios' },
    { label: 'Schemas', value: `${withSchema}/${services.length}`, caption: 'services validated' },
    { label: 'Protocols', value: String(protocols.size), caption: [...protocols].join(' · ') || '—' },
  ];
}

export function StatTiles({ services }: { services: Service[] }) {
  return (
    <div className={styles.group}>
      {summarise(services).map((tile) => (
        <div key={tile.label} className={styles.tile}>
          <p className={styles.label}>{tile.label}</p>
          <p className={styles.value}>{tile.value}</p>
          <p className={styles.caption}>{tile.caption}</p>
        </div>
      ))}
    </div>
  );
}
