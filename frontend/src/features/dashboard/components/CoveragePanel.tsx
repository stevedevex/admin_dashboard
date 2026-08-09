import { Link } from 'react-router';
import type { Service } from '@/api';
import { EmptyState, Icon, Panel, Tag } from '@/ui';
import styles from './CoveragePanel.module.css';

export type CoveragePanelProps = {
  services: Service[];
  state: 'loading' | 'ready' | 'error';
  error: string | null;
};

/**
 * What is being stood in for, and how well each one is furnished.
 *
 * Counts come from the server; nothing here is a ratio. A service's mocks cannot be scored
 * against a total, because there is no such total — an operation keyed on a path variable has as
 * many reachable files as there are values a client might send. Saying "40% covered" would be
 * inventing a denominator, so this reports what exists and flags only the case that is
 * unambiguously a gap: an operation nothing can answer at all.
 */
export function CoveragePanel({ services, state, error }: CoveragePanelProps) {
  if (state === 'error') {
    return (
      <Panel title="Services">
        <EmptyState title="Could not read the catalogue">{error}</EmptyState>
      </Panel>
    );
  }

  if (state === 'loading') {
    return (
      <Panel title="Services">
        <EmptyState title="Loading…" />
      </Panel>
    );
  }

  const unfurnished = services.filter((service) => service.mockCount === 0).length;

  return (
    <Panel
      title="Services"
      actions={
        <>
          {unfurnished > 0 ? <Tag tone="warn">{unfurnished} with no mocks</Tag> : null}
          <Link to="/mock-data/services" className={styles.more}>
            All services
            <Icon name="service" size={13} />
          </Link>
        </>
      }
    >
      {services.length === 0 ? (
        <EmptyState title="No services configured">
          Declare one in the sandbox configuration and it appears here at startup.
        </EmptyState>
      ) : (
        <ul className={styles.list}>
          {services.map((service) => (
            <li key={service.id} className={styles.row}>
              <span className={styles.identity}>
                <span className={styles.name}>{service.name}</span>
                <span className={styles.endpoint} title={service.endpoint}>
                  {service.endpoint}
                </span>
              </span>

              <Tag tone="neutral">{service.protocol}</Tag>

              <span className={styles.count}>
                <span className={service.mockCount === 0 ? styles.figureEmpty : styles.figure}>
                  {service.mockCount}
                </span>
                <span className={styles.unit}>
                  {service.operations.length} {service.operations.length === 1 ? 'op' : 'ops'}
                </span>
              </span>

              {/* A service without a schema still serves; it just cannot have its payloads
                  checked, which is worth knowing before trusting a green validation elsewhere. */}
              <span className={styles.schema}>
                {service.hasSchema ? (
                  <Tag tone="ok" icon={<Icon name="ok" size={11} />}>
                    schema
                  </Tag>
                ) : (
                  <Tag tone="neutral" icon={<Icon name="unknown" size={11} />}>
                    no schema
                  </Tag>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
